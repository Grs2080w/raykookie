package br.com.kntap.raykookie.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.engagement.application.EngagementPurgeJob;
import br.com.kntap.raykookie.engagement.application.EngagementRollupJob;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Medição de engajamento pela rede, com o Postgres real: recebe, não duplica, agrega e responde à dona.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class EngagementWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Tarefa do agregado. */
    @Autowired
    EngagementRollupJob rollup;

    /** Tarefa de limpeza. */
    @Autowired
    EngagementPurgeJob purge;

    /** Token da dona. */
    String owner;

    /**
     * Banco limpo e sessão da dona.
     */
    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM rate_limits").update();
        jdbc.sql("DELETE FROM engagement_events").update();
        jdbc.sql("DELETE FROM engagement_daily").update();
        owner = json().header("x-client", "mobile").body(Map.of("username", "dona", "password", "senha-de-teste-123"))
                .post("/api/auth/login").jsonPath().getString("token");
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    private RequestSpecification json() {
        return RestAssured.given().port(port).contentType(ContentType.JSON);
    }

    /**
     * Requisição da dona.
     *
     * @return a requisição
     */
    private RequestSpecification asOwner() {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + owner);
    }

    /**
     * Evento.
     *
     * @param name   nome
     * @param fields pares chave/valor do evento
     * @return o evento
     */
    private static Map<String, Object> event(String name, Object... fields) {
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("name", name);
        event.put("at", Instant.now().toEpochMilli());
        for (int i = 0; i < fields.length; i += 2) {
            event.put((String) fields[i], fields[i + 1]);
        }
        return event;
    }

    /**
     * Envia um lote.
     *
     * @param session sessão
     * @param events  eventos
     * @return a resposta
     */
    private Response send(UUID session, List<Map<String, Object>> events) {
        return json().body(Map.of("session_id", session.toString(), "platform", "site", "events", events))
                .post("/api/medicao/eventos");
    }

    /**
     * Refaz o agregado de hoje.
     */
    private void aggregate() {
        rollup.run();
    }

    /**
     * Uma visita completa: entrou, olhou, clicou, pediu.
     *
     * @param session sessão
     * @param item    item do cardápio
     * @param order   se chegou a fazer o pedido
     */
    private void visit(UUID session, String item, boolean order) {
        List<Map<String, Object>> events = new ArrayList<>(List.of(
                event("screen_view", "route", "/", "detail", "instagram"),
                event("menu_item_view", "item_id", item, "detail", "landing"),
                event("menu_item_click", "item_id", item, "detail", "landing"),
                event("cart_add", "item_id", item, "qty", 1),
                event("checkout_step", "detail", "data"),
                event("checkout_step", "detail", "delivery"),
                event("checkout_step", "detail", "payment")));
        if (order) {
            events.add(event("checkout_submit", "detail", "ok"));
            events.add(event("order_placed", "detail", "pix"));
        }
        assertThat(send(session, events).statusCode()).isEqualTo(204);
    }

    /**
     * Recebimento e deduplicação.
     */
    @Test
    @DisplayName("recebe o lote (204), descarta o inválido e conta vista e clique uma vez por item e sessão")
    void ingestsWithoutDuplicates() {

        // ── 1. Dois lotes com a mesma vista e o mesmo clique: contam uma vez.
        UUID session = UUID.randomUUID();
        List<Map<String, Object>> batch = List.of(event("menu_item_view", "item_id", "7", "detail", "cardapio"),
                event("menu_item_click", "item_id", "7", "detail", "cardapio"),
                event("coupon_attempt", "detail", "NATAL20"), event("nada", "x", "y"));
        assertThat(send(session, batch).statusCode()).isEqualTo(204);
        assertThat(send(session, batch).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM engagement_events").query(Long.class).single()).isEqualTo(2);

        // ── 2. Outra sessão conta de novo; o carrinho (sem trava) soma.
        assertThat(send(UUID.randomUUID(), batch).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM engagement_events").query(Long.class).single()).isEqualTo(4);
        List<Map<String, Object>> carts = List.of(event("cart_add", "item_id", "7", "qty", 1));
        send(session, carts);
        send(session, carts);
        assertThat(jdbc.sql("SELECT count(*) FROM engagement_events WHERE name = 'cart_add'").query(Long.class)
                .single()).isEqualTo(2);

        // ── 3. Nada de pessoal fica no banco.
        assertThat(jdbc.sql("SELECT count(*) FROM engagement_events WHERE detail = 'NATAL20'").query(Long.class)
                .single()).isZero();
    }

    /**
     * Lote malformado.
     */
    @Test
    @DisplayName("lote malformado é 400; o limite por IP responde 429")
    void refusesMalformedAndFlooding() {
        assertThat(json().body(Map.of("session_id", "x", "platform", "site", "events", List.of(event("screen_view",
                "route", "/")))).post("/api/medicao/eventos").statusCode()).isEqualTo(400);
        assertThat(json().body(Map.of("platform", "tv", "events", List.of())).post("/api/medicao/eventos")
                .statusCode()).isEqualTo(400);
        UUID session = UUID.randomUUID();
        List<Map<String, Object>> one = List.of(event("screen_view", "route", "/"));
        int last = 0;
        for (int i = 0; i < 125 && last != 429; i++) {
            last = send(session, one).statusCode();
        }
        assertThat(last).isEqualTo(429);
    }

    /**
     * Leitura da dona: resumo, funil, receitas, páginas e origens.
     */
    @Test
    @DisplayName("a dona lê resumo, funil, receitas, páginas e origens; só ela")
    void ownerReadsNumbers() {

        // ── 1. Receita e combo da dona, e um pedido pago da receita (a fonte de pedidos e receita).
        long ownerId = jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single();
        long recipe = jdbc.sql("INSERT INTO recipes (user_id, name) VALUES (:u, 'Brownie') RETURNING id")
                .param("u", ownerId).query(Long.class).single();
        long combo = jdbc.sql("INSERT INTO combos (user_id, name) VALUES (:u, 'Combo Festa') RETURNING id")
                .param("u", ownerId).query(Long.class).single();
        long customer = jdbc.sql("INSERT INTO customers (email, name) VALUES (:e, 'C') RETURNING id")
                .param("e", "en-" + UUID.randomUUID() + "@x.com").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                       time_window, payment_method, items, total, public_code, paid_at)
                VALUES (:c, :u, 'C', '1', 'retirada', current_date + 2, 'tarde', 'pix',
                        CAST(:items AS jsonb), 20, (SELECT 10000 + floor(random() * 89999)::int), now())""")
                .param("c", customer).param("u", ownerId)
                .param("items", "[{\"id\":\"" + recipe + "\",\"name\":\"Brownie\",\"qty\":2,\"unit_price\":10}]")
                .update();

        // ── 2. Quatro visitas: três clicam no brownie, uma no combo; uma faz o pedido.
        visit(UUID.randomUUID(), String.valueOf(recipe), true);
        visit(UUID.randomUUID(), String.valueOf(recipe), false);
        visit(UUID.randomUUID(), String.valueOf(recipe), false);
        visit(UUID.randomUUID(), "combo-" + combo, false);
        aggregate();

        // ── 3. Resumo.
        Response overview = asOwner().get("/api/engagement/overview");
        assertThat(overview.statusCode()).isEqualTo(200);
        assertThat(overview.jsonPath().getInt("current.sessions")).isEqualTo(4);
        assertThat(overview.jsonPath().getInt("current.orders")).isEqualTo(1);
        assertThat(overview.jsonPath().getInt("current.paid_orders")).isEqualTo(1);
        assertThat(overview.jsonPath().getFloat("current.revenue")).isEqualTo(20f);
        assertThat(overview.jsonPath().getFloat("current.conversion_pct")).isEqualTo(25f);
        assertThat(overview.jsonPath().getInt("previous.sessions")).isZero();

        // ── 4. Funil: 4 entram, 4 clicam, 4 adicionam, 4 chegam ao pagamento, 1 envia e faz o pedido.
        Response funnel = asOwner().get("/api/engagement/funnel");
        assertThat(funnel.jsonPath().getList("steps.sessions", Integer.class)).containsExactly(4, 4, 4, 4, 4, 4, 1,
                1);
        assertThat(funnel.jsonPath().getFloat("steps[6].pct_of_previous")).isEqualTo(25f);
        assertThat(funnel.jsonPath().getString("biggest_drop.after")).isEqualTo("Chegou ao pagamento");
        assertThat(funnel.jsonPath().getInt("biggest_drop.lost")).isEqualTo(3);

        // ── 5. Receitas: o brownie lidera por cliques; nome, pedidos e receita das tabelas de pedido.
        Response recipes = asOwner().get("/api/engagement/recipes");
        assertThat(recipes.jsonPath().getString("list[0].name")).isEqualTo("Brownie");
        assertThat(recipes.jsonPath().getInt("list[0].views")).isEqualTo(3);
        assertThat(recipes.jsonPath().getInt("list[0].clicks")).isEqualTo(3);
        assertThat(recipes.jsonPath().getInt("list[0].adds")).isEqualTo(3);
        assertThat(recipes.jsonPath().getInt("list[0].orders")).isEqualTo(1);
        assertThat(recipes.jsonPath().getFloat("list[0].revenue")).isEqualTo(20f);
        assertThat(recipes.jsonPath().getString("list[1].name")).isEqualTo("Combo Festa");
        assertThat(asOwner().get("/api/engagement/recipes?sort=revenue").jsonPath().getString("list[0].name"))
                .isEqualTo("Brownie");

        // ── 6. Páginas e origens.
        Response pages = asOwner().get("/api/engagement/pages");
        assertThat(pages.jsonPath().getString("list[0].route")).isEqualTo("/");
        assertThat(pages.jsonPath().getInt("list[0].views")).isEqualTo(4);
        Response sources = asOwner().get("/api/engagement/sources");
        assertThat(sources.jsonPath().getString("referrers[0].kind")).isEqualTo("instagram");
        assertThat(sources.jsonPath().getInt("referrers[0].sessions")).isEqualTo(4);
        assertThat(sources.jsonPath().getString("platforms[0].platform")).isEqualTo("site");

        // ── 7. Filtro de plataforma: nada no app.
        assertThat(asOwner().get("/api/engagement/overview?platform=android").jsonPath()
                .getInt("current.sessions")).isZero();
    }

    /**
     * Acesso e parâmetros.
     */
    @Test
    @DisplayName("sem sessão da dona é 401; período, plataforma e ordenação inválidos são 400")
    void guardsAndValidation() {
        for (String path : List.of("overview", "funnel", "recipes", "pages", "sources")) {
            assertThat(RestAssured.given().port(port).get("/api/engagement/" + path).statusCode()).isEqualTo(401);
        }
        assertThat(asOwner().get("/api/engagement/overview?from=ontem").statusCode()).isEqualTo(400);
        assertThat(asOwner().get("/api/engagement/overview?from=2026-02-01&to=2026-01-01").statusCode())
                .isEqualTo(400);
        assertThat(asOwner().get("/api/engagement/overview?from=2020-01-01&to=2026-01-01").statusCode())
                .isEqualTo(400);
        assertThat(asOwner().get("/api/engagement/overview?platform=tv").statusCode()).isEqualTo(400);
        assertThat(asOwner().get("/api/engagement/recipes?sort=nome").statusCode()).isEqualTo(400);
        assertThat(asOwner().get("/api/engagement/overview?from=2026-01-01&to=2026-01-31").statusCode())
                .isEqualTo(200);
    }

    /**
     * Retenção.
     */
    @Test
    @DisplayName("a limpeza apaga o evento bruto com mais de 90 dias e o agregado com mais de 13 meses")
    void purgeKeepsRetention() {
        jdbc.sql("""
                INSERT INTO engagement_events (occurred_at, session_id, platform, name, route)
                VALUES (now() - interval '91 days', gen_random_uuid(), 'site', 'screen_view', '/'),
                       (now() - interval '10 days', gen_random_uuid(), 'site', 'screen_view', '/')""").update();
        jdbc.sql("""
                INSERT INTO engagement_daily (day, platform, name, route, item_id, detail, events, sessions)
                VALUES (current_date - 500, 'site', 'screen_view', '*', '*', '*', 1, 1),
                       (current_date - 100, 'site', 'screen_view', '*', '*', '*', 1, 1)""").update();

        purge.run();

        assertThat(jdbc.sql("SELECT count(*) FROM engagement_events").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM engagement_daily").query(Long.class).single()).isEqualTo(1);
        assertThat(rollup.name()).isEqualTo("engagement.rollup");
        assertThat(purge.name()).isEqualTo("engagement.purge");
        assertThat(rollup.runsAlongsideNode() && purge.runsAlongsideNode()).isTrue();
        assertThat(rollup.frequency()).isNotNull();
        assertThat(purge.frequency()).isNotNull();
    }
}
