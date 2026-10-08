package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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
 * Pedidos pela rede, com o Postgres real: criação, lista do cliente, cancelamento, limites (G11) e avisos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class OrdersWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Portas de mentira e avisos. */
    @Autowired
    FakePorts fakes;

    /** Pedidos vistos pelos outros módulos. */
    @Autowired
    OrdersApi ordersApi;

    /** Token da dona. */
    String owner;

    /** Token do cliente. */
    String customer;

    /** Item do cardápio. */
    String item;

    /**
     * Limpa os limites, entra como a dona, cadastra um cliente e põe uma receita no cardápio.
     */
    @BeforeEach
    void setUp() {

        // ── 1. Sessões.
        jdbc.sql("DELETE FROM rate_limits").update();
        owner = api().header("x-client", "mobile").body(Map.of("username", "dona", "password", "senha-de-teste-123"))
                .post("/api/auth/login").jsonPath().getString("token");
        customer = api().header("x-client", "mobile").body(Map.of("name", "Cliente Pedido", "email",
                "ped-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro").jsonPath().getString("token");

        // ── 2. Uma receita a R$ 9,50 no cardápio.
        long recipe = jdbc.sql("""
                INSERT INTO recipes (user_id, name, sale_price)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 9.5) RETURNING id""")
                .param("name", "Cookie IT " + UUID.randomUUID().toString().substring(0, 6)).query(Long.class).single();
        long flour = jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 'g', 1000, 10) RETURNING id""")
                .param("name", "Farinha " + recipe).query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 50)")
                .param("r", recipe).param("i", flour).update();
        item = String.valueOf(recipe);
        fakes.settled = false;
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    private RequestSpecification api() {
        return RestAssured.given().port(port).contentType(ContentType.JSON);
    }

    /**
     * Requisição com sessão.
     *
     * @param token token
     * @param body  corpo
     * @return a requisição
     */
    private RequestSpecification as(String token, Object body) {
        return api().header("Authorization", "Bearer " + token).body(body);
    }

    /**
     * Pedido válido para depois de amanhã.
     *
     * @param payment forma de pagamento
     * @return o corpo
     */
    private Map<String, Object> order(String payment) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("id", item, "qty", 2)));
        body.put("contact_name", "Maria Souza");
        body.put("phone", "11912345678");
        body.put("fulfillment", "retirada");
        body.put("scheduled_for", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString());
        body.put("time_window", "tarde");
        body.put("payment_method", payment);
        return body;
    }

    /**
     * Cria um pedido.
     *
     * @param payment forma de pagamento
     * @return a resposta
     */
    private Response create(String payment) {
        return as(customer, order(payment)).post("/api/cliente/pedidos");
    }

    /**
     * Cliente do pedido.
     *
     * @param orderId pedido da loja
     * @return o cliente
     */
    private CustomerId customerOf(long orderId) {
        return new CustomerId(jdbc.sql("SELECT customer_id FROM shop_orders WHERE id = :id").param("id", orderId)
                .query(Long.class).single());
    }

    /**
     * Pix: a porta de pagamentos gera o código e o pedido aparece para o cliente.
     */
    @Test
    @DisplayName("pedido com Pix: 201 com o código da porta de pagamentos; aviso à dona; aparece na lista")
    void pixOrder() {

        // ── 1. Cria.
        Response created = create("pix");
        assertThat(created.statusCode()).isEqualTo(201);
        long id = created.jsonPath().getLong("id");
        assertThat(created.jsonPath().getString("pix.code")).isEqualTo("pix-" + id);
        assertThat(created.jsonPath().getString("card")).isNull();
        assertThat(fakes.pix).anyMatch(order -> order.id() == id && order.total().cents() == 1900
                && order.email().startsWith("ped-"));
        assertThat(fakes.notices).anyMatch(n -> n instanceof OwnerNotice.NewOrder o && o.orderId() == id);

        // ── 2. Na tela Pedidos e na lista do cliente.
        assertThat(jdbc.sql("SELECT notes FROM orders WHERE shop_order_id = :id").param("id", id)
                .query(String.class).single()).contains("Pagamento: Pix").contains("Tel (11) 91234-5678");
        Response list = api().header("Authorization", "Bearer " + customer).get("/api/cliente/pedidos");
        assertThat(list.jsonPath().getString("find { it.id == " + id + " }.payment_state")).isEqualTo("pendente");
        assertThat(ordersApi.customerOrder(customerOf(id), id)).get().extracting(o -> o.get("total")).isEqualTo(19L);
    }

    /**
     * Mudança de situação pela dona avisa o cliente.
     */
    @Test
    @DisplayName("dona muda a linha para produção: o cliente é avisado e o cancelamento vira 409")
    void statusChangeNotifiesCustomer() {
        long id = create("dinheiro").jsonPath().getLong("id");
        long line = jdbc.sql("SELECT id FROM orders WHERE shop_order_id = :id").param("id", id).query(Long.class)
                .single();
        assertThat(as(owner, Map.of("status", "producao")).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        assertThat(fakes.notices).anyMatch(n -> n instanceof CustomerNotice.Status s && s.order().id() == id
                && "producao".equals(s.status()));
        Response refused = as(customer, Map.of()).post("/api/cliente/pedidos/" + id + "/cancelar");
        assertThat(refused.statusCode()).isEqualTo(409);
    }

    /**
     * Cancelamento sem pagamento e com Pix pago agora.
     */
    @Test
    @DisplayName("cancelar: sem pagamento cancela e registra o histórico; Pix pago agora segue pela devolução")
    void cancel() {

        // ── 1. Sem pagamento.
        long id = create("dinheiro").jsonPath().getLong("id");
        Response cancelled = as(customer, Map.of("reason_code", "outro", "reason", "mudei de ideia"))
                .post("/api/cliente/pedidos/" + id + "/cancelar");
        assertThat(cancelled.jsonPath().getString("state")).isEqualTo("cancelled");
        assertThat(ordersApi.customerOrders(customerOf(id)))
                .anyMatch(o -> ((Number) o.get("id")).longValue() == id && "cancelado".equals(o.get("status")));
        assertThat(jdbc.sql("SELECT type FROM order_events WHERE shop_order_id = :id").param("id", id)
                .query(String.class).list()).containsExactly("cancelado");
        assertThat(fakes.notices).anyMatch(n -> n instanceof OwnerNotice.Cancelled c && c.orderId() == id
                && "mudei de ideia".equals(c.reason()));

        // ── 2. Pix que acabou de ser pago: devolução.
        long pix = create("pix").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET mp_payment_id = '123' WHERE id = :id").param("id", pix).update();
        fakes.settled = true;
        Response refunded = as(customer, Map.of()).post("/api/cliente/pedidos/" + pix + "/cancelar");
        assertThat(refunded.jsonPath().getDouble("refunded")).isEqualTo(9.5);
        assertThat(fakes.refunded).contains(pix);

        // ── 3. Cancelamento pela dona vai para a devolução.
        assertThat(as(owner, Map.of("code", "sem_estoque")).post("/api/orders/shop/" + id + "/cancelar")
                .jsonPath().getBoolean("ok")).isTrue();
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/abc/cancelar").statusCode()).isEqualTo(404);
    }

    /**
     * Limites por cliente (G11).
     */
    @Test
    @DisplayName("G11: 4º pedido online sem pagar é 429; 11º pedido na hora é 429")
    void limits() {

        // ── 1. Três Pix em aberto: o quarto é recusado.
        for (int i = 0; i < 3; i++) {
            assertThat(create("pix").statusCode()).isEqualTo(201);
        }
        Response fourth = create("pix");
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.jsonPath().getString("error"))
                .isEqualTo("Você fez muitos pedidos em pouco tempo. Tente de novo mais tarde.");

        // ── 2. Pagos, saem da conta; o limite por hora vale para qualquer forma de pagamento.
        jdbc.sql("UPDATE shop_orders SET paid_at = now() WHERE payment_method = 'pix' AND paid_at IS NULL").update();
        for (int i = 0; i < 7; i++) {
            assertThat(create("dinheiro").statusCode()).isEqualTo(201);
        }
        assertThat(create("dinheiro").statusCode()).isEqualTo(429);
    }
}
