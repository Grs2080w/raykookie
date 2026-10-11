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
}
