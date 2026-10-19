package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.catalog.application.IngredientUseCases;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Histórico de auditoria (Fase 2): cada ação que mexe em estoque, receita, preço, cupom ou pedido grava a linha certa
 * na mesma transação; o histórico não pode ser alterado nem apagado; só a dona lê, e só o dela.
 */
class AuditLogIT extends AppItSupport {

    /** Transação programática, para provar que ação e histórico andam juntos. */
    @Autowired
    TransactionTemplate transactions;

    /** Casos de uso de ingredientes. */
    @Autowired
    IngredientUseCases ingredientUseCases;

    /** Histórico de auditoria. */
    @Autowired
    AuditTrail trail;

    /** Sessão da dona. */
    private String owner;

    /**
     * Sessão nova e o histórico de antes do teste, para contar só o que o teste faz.
     */
    @BeforeEach
    void session() {
        owner = panelSession(OWNER);
    }

    /**
     * Linhas do histórico da dona, da mais nova para a mais antiga.
     *
     * @param category categoria
     * @return as linhas
     */
    private List<Map<String, Object>> rows(String category) {
        return jdbc.sql("""
                SELECT a.* FROM audit_log a JOIN users u ON u.id = a.owner_id
                 WHERE u.username = :owner AND a.category = :category ORDER BY a.id DESC""")
                .param("owner", OWNER).param("category", category).query().listOfRows();
    }

    /**
     * Lista do histórico com parâmetros de query (o RestAssured codifica espaço, % e aspas).
     *
     * @param params pares nome/valor
     * @return a resposta
     */
    private Response list(String... params) {
        io.restassured.specification.RequestSpecification request = as(owner);
        for (int i = 0; i < params.length; i += 2) {
            request = request.queryParam(params[i], params[i + 1]);
        }
        return request.get("/api/historico");
    }

    private long ingredient(String name, int cost, int stock) {
        return as(owner).body(Map.of("name", name, "unit", "g", "package_size", 1000, "package_cost", cost,
                "stock_qty", stock)).post("/api/ingredients").then().statusCode(201).extract().jsonPath()
                .getLong("id");
    }

    /**
     * Estoque: criar, ajustar, editar (com preço) e excluir.
     */
    @Test
    @DisplayName("estoque: criou, ajustou, editou (com preço) e excluiu geram linhas com quem, onde, IP e antes/depois")
    void stockActionsAreLogged() {
        long id = ingredient("Farinha Audit", 10, 1000);
        Map<String, Object> created = rows("estoque").get(0);
        assertThat(created.get("action")).isEqualTo("criou");
        assertThat(created.get("user_name")).isEqualTo(OWNER);
        assertThat(created.get("role")).isEqualTo("dona");
        assertThat(created.get("location")).isEqualTo("Gestão > Estoque");
        assertThat(created.get("route")).isEqualTo("POST /api/ingredients");
        assertThat(created.get("record_id")).isEqualTo(String.valueOf(id));
        assertThat(created.get("ip")).isNotNull();
        assertThat(created.get("old_value")).isNull();
        assertThat(created.get("occurred_at")).isNotNull();

        // ajuste só do estoque
        as(owner).body(Map.of("stock_qty", 800)).patch("/api/ingredients/" + id).then().statusCode(200);
        Map<String, Object> adjust = rows("estoque").get(0);
        assertThat(adjust.get("action")).isEqualTo("ajustou");
        assertThat(adjust.get("details").toString()).contains("Farinha Audit").contains("1000").contains("800");
        assertThat(adjust.get("old_value").toString()).contains("1000");
        assertThat(adjust.get("new_value").toString()).contains("800");

        // edição completa com preço novo: estoque editou + preço editou
        as(owner).body(Map.of("name", "Farinha Audit", "unit", "g", "package_size", 1000, "package_cost", 14))
                .patch("/api/ingredients/" + id).then().statusCode(200);
        assertThat(rows("estoque").get(0).get("action")).isEqualTo("editou");
        Map<String, Object> price = rows("preco").get(0);
        assertThat(price.get("details").toString()).contains("R$ 10").contains("R$ 14");

        // excluir
        as(owner).delete("/api/ingredients/" + id).then().statusCode(200);
        Map<String, Object> deleted = rows("estoque").get(0);
        assertThat(deleted.get("action")).isEqualTo("excluiu");
        assertThat(deleted.get("new_value")).isNull();
        assertThat(deleted.get("old_value")).isNotNull();
    }

    /**
     * Receita: criada, preço de venda alterado e excluída.
     */
    @Test
    @DisplayName("receitas: criou, editou, mudança de preço de venda e excluiu entram no histórico")
    void recipeActionsAreLogged() {
        long flour = ingredient("Açúcar Audit", 5, 500);
        Map<String, Object> body = new HashMap<>(Map.of("name", "Bolo Audit", "yield_units", 8, "sale_price", "30",
                "items", List.of(Map.of("ingredient_id", flour, "qty", 300))));
        long recipe = as(owner).body(body).post("/api/recipes").then().statusCode(201).extract().jsonPath()
                .getLong("id");
        assertThat(rows("receita").get(0).get("action")).isEqualTo("criou");
        assertThat(rows("receita").get(0).get("new_value").toString()).contains("Bolo Audit").contains("items");

        body.put("sale_price", "35");
        as(owner).body(body).put("/api/recipes/" + recipe).then().statusCode(200);
        assertThat(rows("receita").get(0).get("action")).isEqualTo("editou");
        Map<String, Object> price = rows("preco").get(0);
        assertThat(price.get("details").toString()).contains("Bolo Audit").contains("R$ 30").contains("R$ 35");

        as(owner).delete("/api/recipes/" + recipe).then().statusCode(200);
        assertThat(rows("receita").get(0).get("action")).isEqualTo("excluiu");
    }

    /**
     * Cupons: criar, desativar, editar e excluir.
     */
    @Test
    @DisplayName("cupons: criou, desativou, editou o desconto e excluiu")
    void couponActionsAreLogged() {
        String code = "AUD" + System.nanoTime() % 100000;
        JsonPath created = as(owner).body(Map.of("code", code, "percent", 10)).post("/api/coupons").then()
                .statusCode(201).extract().jsonPath();
        long id = created.getLong("find { it.code == '" + code + "' }.id");
        assertThat(rows("cupom").get(0).get("action")).isEqualTo("criou");

        as(owner).body(Map.of("percent", 10, "active", false)).put("/api/coupons/" + id).then().statusCode(200);
        assertThat(rows("cupom").get(0).get("action")).isEqualTo("desativou");

        as(owner).body(Map.of("percent", 25, "active", false)).put("/api/coupons/" + id).then().statusCode(200);
        Map<String, Object> edited = rows("cupom").get(0);
        assertThat(edited.get("action")).isEqualTo("editou");
        assertThat(edited.get("details").toString()).contains("10%").contains("25%");

        as(owner).delete("/api/coupons/" + id).then().statusCode(200);
        assertThat(rows("cupom").get(0).get("action")).isEqualTo("excluiu");
    }

    /**
     * Pedidos lançados à mão e o dinheiro deles.
     */
    @Test
    @DisplayName("pedidos: criar, marcar pago (financeiro) e excluir")
    void manualOrderActionsAreLogged() {
        Map<String, Object> body = new HashMap<>(Map.of("customer", "Ana Audit", "product", "Bolo", "qty", "2",
                "unit_price", "45,50", "delivery_date", "2026-12-20"));
        long id = as(owner).body(body).post("/api/orders").then().statusCode(201).extract().jsonPath().getLong("id");
        assertThat(rows("pedido").get(0).get("action")).isEqualTo("criou");
        assertThat(rows("financeiro").get(0).get("details").toString()).contains("2 x R$ 45.5");

        as(owner).body(Map.of("payment", "pago")).patch("/api/orders/" + id).then().statusCode(200);
        assertThat(rows("pedido").get(0).get("action")).isEqualTo("editou");
        assertThat(rows("financeiro").get(0).get("action")).isEqualTo("pagou");

        as(owner).delete("/api/orders/" + id).then().statusCode(200);
        assertThat(rows("pedido").get(0).get("action")).isEqualTo("excluiu");
    }

    /**
     * Pedido do cliente com cupom: o cupom "aplicou" é do cliente, com o desconto calculado no servidor.
     */
    @Test
    @DisplayName("pedido online com cupom: 'aplicou' pelo cliente, com o desconto do servidor; cancelar também entra")
    void customerOrderWithCouponIsLogged() {
        String code = "APL" + System.nanoTime() % 100000;
        as(owner).body(Map.of("code", code, "percent", 20)).post("/api/coupons").then().statusCode(201);
        String item = menuItem();
        Customer customer = newCustomer();
        Map<String, Object> order = order(item, "dinheiro");
        order.put("coupon_code", code);
        long id = as(customer.token()).body(order).post("/api/cliente/pedidos").then().statusCode(201).extract()
                .jsonPath().getLong("id");

        Map<String, Object> applied = rows("cupom").get(0);
        assertThat(applied.get("action")).isEqualTo("aplicou");
        assertThat(applied.get("role")).isEqualTo("cliente");
        assertThat(applied.get("details").toString()).contains(code).contains("R$ 3.8"); // 20% de 19,00
        assertThat(rows("pedido").get(0).get("action")).isEqualTo("criou");

        as(customer.token()).body(Map.of("reason_code", "outro", "reason", "mudei de ideia"))
                .post("/api/cliente/pedidos/" + id + "/cancelar")
                .then().statusCode(200);
        assertThat(rows("pedido").get(0).get("action")).isEqualTo("cancelou");
    }

    /**
     * Ação que falha não registra; ação desfeita desfaz o registro junto.
     */
    @Test
    @DisplayName("mesma transação: ação recusada não grava; ação desfeita (rollback) leva o histórico junto")
    void actionAndHistoryShareOneTransaction() {
        // ── 1. Ação recusada (ingrediente usado numa receita) não deixa rastro.
        long flour = ingredient("Usado Audit", 5, 100);
        as(owner).body(Map.of("name", "Torta Audit", "yield_units", 4, "items",
                List.of(Map.of("ingredient_id", flour, "qty", 100)))).post("/api/recipes").then().statusCode(201);
        long before = count("estoque");
        assertThat(as(owner).delete("/api/ingredients/" + flour).statusCode()).isEqualTo(409);
        assertThat(count("estoque")).isEqualTo(before);

        // ── 2. Rollback: nem o ingrediente nem a linha do histórico sobrevivem.
        long ownerId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", OWNER).query(Long.class)
                .single();
        long ingredients = jdbc.sql("SELECT count(*) FROM ingredients").query(Long.class).single();
        long logged = count("estoque");
        transactions.executeWithoutResult(status -> {
            ingredientUseCases.create(new OwnerId(ownerId), Map.of("name", "Fantasma", "unit", "g", "package_size",
                    1000, "package_cost", 1));
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM ingredients").query(Long.class).single()).isEqualTo(ingredients);
        assertThat(count("estoque")).isEqualTo(logged);
    }

    /**
     * Sem transação aberta o histórico se recusa a gravar.
     */
    @Test
    @DisplayName("gravar histórico fora de uma transação lança erro (não passa em silêncio)")
    void recordingWithoutTransactionFails() {
        long ownerId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", OWNER).query(Long.class)
                .single();
        assertThatThrownBy(() -> trail.record(new OwnerId(ownerId), AuditTrail.Entry.of(
                "estoque", "criou", "ingredient", 1, null, Map.of("name", "x"), "sem transação")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transação");
    }

    /**
     * Imutável: nem SQL direto altera ou apaga, nem a API tem rota para isso.
     */
    @Test
    @DisplayName("somente inserção: UPDATE, DELETE e TRUNCATE no banco são recusados; a API não tem rota de escrita")
    void historyIsAppendOnly() {
        ingredient("Imutável Audit", 1, 1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET details = 'adulterado'").update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("somente inserção");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update())
                .hasStackTraceContaining("somente inserção");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_log").update()).hasStackTraceContaining("somente inserção");

        long id = jdbc.sql("SELECT max(id) FROM audit_log").query(Long.class).single();
        for (String path : List.of("/api/historico", "/api/historico/" + id)) {
            assertThat(as(owner).body(Map.of("details", "x")).put(path).statusCode()).isIn(404, 405);
            assertThat(as(owner).body(Map.of("details", "x")).patch(path).statusCode()).isIn(404, 405);
            assertThat(as(owner).delete(path).statusCode()).isIn(404, 405);
        }
        assertThat(as(owner).body(Map.of("details", "x")).post("/api/historico").statusCode()).isIn(404, 405);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE details = 'adulterado'").query(Long.class)
                .single()).isZero();
    }

    /**
     * Quem lê: só a dona, e só o histórico dela.
     */
    @Test
    @DisplayName("leitura: sem sessão, token falso, cliente e outra conta do painel não leem o histórico (401/403)")
    void onlyTheOwnerReadsHerHistory() {
        ingredient("Privado Audit", 1, 1);
        assertThat(api().get("/api/historico").statusCode()).isEqualTo(401);
        assertThat(as(newCustomer().token()).get("/api/historico").statusCode()).isIn(401, 403);
        assertThat(as("token-falso").get("/api/historico").statusCode()).isEqualTo(401);

        JsonPath mine = list("q", "Privado Audit").jsonPath();
        assertThat(mine.getInt("total")).isGreaterThanOrEqualTo(1);
        // outra conta do painel (que não é a dona da loja) nem chega a ler o histórico
        assertThat(as(panelSession(STRANGER)).queryParam("q", "Privado Audit").get("/api/historico").statusCode())
                .isIn(401, 403);
    }

    /**
     * Lista paginada com filtros.
     */
    @Test
    @DisplayName("lista: mais recentes primeiro, paginada, com filtros de categoria, ação, usuário, busca e período")
    void listFiltersAndPagination() {
        for (int i = 0; i < 5; i++) {
            ingredient("Paginação Audit " + i, 1, i);
        }
        JsonPath all = list("per_page", "2", "category", "estoque", "q", "Paginação").jsonPath();
        assertThat(all.getInt("per_page")).isEqualTo(2);
        assertThat(all.getList("items")).hasSize(2);
        assertThat(all.getLong("total")).isEqualTo(5);
        assertThat(all.getString("items[0].details")).contains("Paginação Audit 4"); // mais recente primeiro
        JsonPath next = list("per_page", "2", "page", "3", "category", "estoque", "q", "Paginação").jsonPath();
        assertThat(next.getList("items")).hasSize(1);

        assertThat(list("per_page", "100000").jsonPath().getInt("per_page")).isEqualTo(100);
        assertThat(list("action", "excluiu", "q", "Paginação").jsonPath().getLong("total")).isZero();
        assertThat(list("user", OWNER, "q", "Paginação").jsonPath().getLong("total")).isEqualTo(5);
        assertThat(list("user", "ninguem", "q", "Paginação").jsonPath().getLong("total")).isZero();
        assertThat(list("from", "2999-01-01T00:00:00Z").jsonPath().getLong("total")).isZero();
        assertThat(list("to", "2000-01-01T00:00:00Z").jsonPath().getLong("total")).isZero();
        assertThat(list("from", "ontem").statusCode()).isEqualTo(400);
        assertThat(as(owner).get("/api/historico").jsonPath().getList("filters.categories")).contains("estoque");
    }

    /**
     * Busca e filtros são texto, nunca SQL ou curinga.
     */
    @Test
    @DisplayName("busca: % e _ valem como texto; payload de SQL não quebra nem vaza")
    void searchTreatsInputAsText() {
        ingredient("Cem% Audit", 1, 1);
        assertThat(list("q", "Cem% Audit").jsonPath().getLong("total")).isEqualTo(1);
        long withPercent = jdbc.sql("""
                SELECT count(*) FROM audit_log WHERE position('%' in details || user_name || location || record_id
                       || record_kind) > 0""").query(Long.class).single();
        long total = as(owner).get("/api/historico").jsonPath().getLong("total");
        long literal = list("q", "%").jsonPath().getLong("total");
        assertThat(literal).as("% é texto, não curinga").isEqualTo(withPercent).isLessThan(total);
        Response attack = list("q", "' OR '1'='1", "category", "'; DROP TABLE audit_log;--");
        assertThat(attack.statusCode()).isEqualTo(200);
        assertThat(attack.jsonPath().getLong("total")).isZero();
        assertThat(count("estoque")).isGreaterThan(0);
    }

    /**
     * Venda Presencial: o aparelho informa o QR gerado e a chave; a chave nunca chega.
     */
    @Test
    @DisplayName("pix presencial: eventos do aparelho entram como 'informado pelo aparelho'; chave nunca é gravada")
    void pixPresencialEventsAreLogged() {
        Map<String, Object> qr = Map.of("kind", "qr_gerado", "title", "Cookie Red Velvet", "amount", 12.5, "at",
                "2026-10-18T10:00:00-03:00");
        Map<String, Object> key = new HashMap<>(Map.of("kind", "chave_alterada", "key_kind", "cpf"));
        key.put("pix_key", "52998224725"); // o app não manda, mas se mandar não pode ser gravada
        assertThat(as(owner).body(Map.of("events", List.of(qr, key))).post("/api/historico/pix-presencial")
                .jsonPath().getInt("saved")).isEqualTo(2);

        List<Map<String, Object>> rows = rows("pix_presencial");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("action")).isEqualTo("gerou");
        assertThat(rows.get(1).get("location")).isEqualTo("Venda Presencial");
        assertThat(rows.get(1).get("details").toString()).contains("Cookie Red Velvet").contains("12.5");
        assertThat(rows.get(1).get("new_value").toString()).contains("reported_by_device");
        assertThat(rows.toString()).doesNotContain("52998224725");
    }

    /**
     * Eventos adulterados são recusados inteiros.
     */
    @Test
    @DisplayName("pix presencial: valor negativo, zero, texto, tipo inválido, lote vazio ou enorme: 400 e nada gravado")
    void pixPresencialRejectsBadEvents() {
        long before = count("pix_presencial");
        for (Object bad : List.of(
                Map.of("kind", "qr_gerado", "title", "x", "amount", -1),
                Map.of("kind", "qr_gerado", "title", "x", "amount", 0),
                Map.of("kind", "qr_gerado", "title", "x", "amount", "abc"),
                Map.of("kind", "qr_gerado", "title", "x", "amount", 1_000_000),
                Map.of("kind", "apagar_tudo"),
                "texto")) {
            assertThat(as(owner).body(Map.of("events", List.of(bad))).post("/api/historico/pix-presencial")
                    .statusCode()).as("%s", bad).isEqualTo(400);
        }
        assertThat(as(owner).body(Map.of("events", List.of())).post("/api/historico/pix-presencial").statusCode())
                .isEqualTo(400);
        assertThat(as(owner).body(Map.of()).post("/api/historico/pix-presencial").statusCode()).isEqualTo(400);
        List<Object> many = java.util.Collections.nCopies(51, Map.of("kind", "qr_gerado", "title", "x", "amount", 1));
        assertThat(as(owner).body(Map.of("events", many)).post("/api/historico/pix-presencial").statusCode())
                .isEqualTo(400);
        assertThat(api().body(Map.of("events", List.of(Map.of("kind", "qr_gerado", "title", "x", "amount", 1))))
                .post("/api/historico/pix-presencial").statusCode()).isEqualTo(401);
        assertThat(count("pix_presencial")).isEqualTo(before);
    }

    private long count(String category) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE category = :c").param("c", category).query(Long.class)
                .single();
    }
}
