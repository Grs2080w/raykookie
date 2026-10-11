package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Combos e cupons de ponta a ponta: a dona monta na página do Cardápio, o cliente vê e pede, o pedido sai com o
 * valor certo e as métricas contam.
 */
class MenuFeaturesIT extends AppItSupport {

    /**
     * Lucro do mês nas metas.
     *
     * @param owner sessão da dona
     * @return o lucro
     */
    private double profit(String owner) {
        return as(owner).get("/api/goals").jsonPath().getDouble("stats.profit");
    }

    /**
     * Combo do começo ao fim.
     */
    @Test
    @DisplayName("combo: aparece primeiro no cardápio, é pedido como uma linha e conta no lucro do mês")
    void comboEndToEnd() {

        // ── 1. Dois produtos a R$ 9,50 e um combo 2 + 1 com o preço sugerido (R$ 28,50 - 10% = R$ 26).
        String owner = panelSession(OWNER);
        String a = menuItem();
        String b = menuItem();
        JsonPath page = as(owner).body(Map.of("name", "Combo E2E", "items", List.of(
                Map.of("recipe_id", Long.parseLong(a), "qty", 2), Map.of("recipe_id", Long.parseLong(b), "qty", 1))))
                .post("/api/combos").then().statusCode(201).extract().jsonPath();
        String combo = "combos.find { it.name == 'Combo E2E' }";
        long comboId = page.getLong(combo + ".id");
        double cost = page.getDouble(combo + ".cost");
        assertThat(page.getDouble(combo + ".final_price")).isEqualTo(26.0);

        // ── 2. No cardápio: grupo Combos primeiro, com o resumo e a economia.
        JsonPath menu = api().get("/api/menu").jsonPath();
        assertThat(menu.getString("groups[0].name")).isEqualTo("Combos");
        String item = "items.find { it.id == 'combo-" + comboId + "' }";
        assertThat(menu.getDouble(item + ".price")).isEqualTo(26.0);
        assertThat(menu.getString(item + ".combo.contents")).startsWith("2 Cookie E2E").contains(" + 1 Cookie E2E");
        assertThat(menu.getDouble(item + ".combo.savings")).isEqualTo(2.5);

        // ── 3. O cliente pede 2 combos: uma linha de combo, total R$ 52.
        double before = profit(owner);
        pixAnswers();
        Response created = as(newCustomer().token()).body(order("combo-" + comboId, "pix"))
                .post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        long orderId = created.jsonPath().getLong("id");
        Map<String, Object> line = jdbc.sql("SELECT combo_id, recipe_id, product, qty, unit_price::float8 AS price "
                + "FROM orders WHERE shop_order_id = :id").param("id", orderId).query().singleRow();
        assertThat(((Number) line.get("combo_id")).longValue()).isEqualTo(comboId);
        assertThat(line.get("recipe_id")).isNull();
        assertThat((String) line.get("product")).startsWith("Combo E2E (2 Cookie E2E");
        assertThat(((Number) line.get("qty")).intValue()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT total::float8 FROM shop_orders WHERE id = :id").param("id", orderId)
                .query(Double.class).single()).isEqualTo(52.0);

        // ── 4. Lucro do mês sobe o vendido menos o custo dos produtos.
        assertThat(profit(owner) - before).isCloseTo(2 * (26.0 - cost), offset(0.01));
    }

    /**
     * Cupom do começo ao fim.
     */
    @Test
    @DisplayName("cupom: mínimo 'a partir de', conferência, pedido com desconto, uso, exclusão barrada e desativado")
    void couponEndToEnd() {

        // ── 1. A dona cria NATAL20 (20%, a partir de R$ 15) e um gerado pelo sistema.
        String owner = panelSession(OWNER);
        String code = "NATAL" + (System.nanoTime() % 100_000);
        as(owner).body(Map.of("code", code.toLowerCase(), "percent", 20, "min_total", 15, "min_inclusive", true))
                .post("/api/coupons").then().statusCode(201);
        JsonPath list = as(owner).body(Map.of("percent", 10)).post("/api/coupons").then().statusCode(201).extract()
                .jsonPath();
        assertThat(list.getList("code", String.class)).contains(code).anyMatch(c -> c.matches("RAY-[A-Z2-9]{5}"));
        assertThat(as(owner).body(Map.of("code", code, "percent", 5)).post("/api/coupons").statusCode())
                .isEqualTo(409);
        long id = list.getLong("find { it.code == '" + code + "' }.id");

        // ── 2. Conferência: 1 item (R$ 9,50) falta R$ 5,50; 2 itens (R$ 19) dão R$ 3,80 de desconto.
        String item = menuItem();
        Customer customer = newCustomer();
        Response low = as(customer.token()).body(Map.of("code", code, "items", List.of(Map.of("id", item, "qty", 1))))
                .post("/api/cliente/cupom");
        assertThat(low.statusCode()).isEqualTo(400);
        assertThat(low.jsonPath().getString("error")).isEqualTo(
                "Este cupom vale para pedidos a partir de R$ 15,00 — faltam R$ 5,50.");
        JsonPath ok = as(customer.token()).body(Map.of("code", " " + code.toLowerCase() + " ",
                "items", List.of(Map.of("id", item, "qty", 2)))).post("/api/cliente/cupom").then().statusCode(200)
                .extract().jsonPath();
        assertThat(ok.getDouble("discount")).isEqualTo(3.8);
        assertThat(ok.getDouble("total")).isEqualTo(15.2);

        // ── 3. Pedido com o cupom: total já com desconto, o cupom gravado e na nota da tela Pedidos.
        double revenueBefore = as(owner).get("/api/goals").jsonPath().getDouble("stats.revenue");
        pixAnswers();
        Map<String, Object> body = order(item, "pix");
        body.put("coupon_code", code);
        long orderId = as(customer.token()).body(body).post("/api/cliente/pedidos").then().statusCode(201)
                .extract().jsonPath().getLong("id");
        Map<String, Object> row = jdbc.sql("SELECT total::float8 AS total, subtotal::float8 AS subtotal, coupon_code, "
                + "discount_total::float8 AS discount FROM shop_orders WHERE id = :id").param("id", orderId).query()
                .singleRow();
        assertThat(row).containsEntry("total", 15.2).containsEntry("subtotal", 19.0).containsEntry("discount", 3.8)
                .containsEntry("coupon_code", code);
        assertThat(jdbc.sql("SELECT notes FROM orders WHERE shop_order_id = :id").param("id", orderId)
                .query(String.class).single()).contains("Cupom " + code + " (-20%): -R$ 3,80");
        assertThat(as(owner).get("/api/goals").jsonPath().getDouble("stats.revenue") - revenueBefore)
                .isCloseTo(15.2, offset(0.01));
        assertThat(as(customer.token()).get("/api/cliente/pedidos").jsonPath()
                .getDouble("find { it.id == " + orderId + " }.discount_total")).isEqualTo(3.8);

        // ── 4. Usado: não exclui; desativado: vira inválido.
        assertThat(as(owner).get("/api/coupons").jsonPath().getInt("find { it.id == " + id + " }.uses")).isEqualTo(1);
        assertThat(as(owner).delete("/api/coupons/" + id).statusCode()).isEqualTo(409);
        as(owner).body(Map.of("percent", 20, "min_total", 15, "active", false)).put("/api/coupons/" + id).then()
                .statusCode(200);
        Response off = as(customer.token()).body(Map.of("code", code, "items", List.of(Map.of("id", item, "qty", 2))))
                .post("/api/cliente/cupom");
        assertThat(off.jsonPath().getString("error")).isEqualTo("Cupom inválido.");
    }
}
