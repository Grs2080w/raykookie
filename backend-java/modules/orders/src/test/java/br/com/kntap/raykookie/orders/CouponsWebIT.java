package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cupons pela rede, com o Postgres real: a aba da dona, a conferência do cliente e o pedido com desconto.
 */
class CouponsWebIT extends OrdersItSupport {

    /**
     * Do cadastro ao pedido.
     */
    @Test
    @DisplayName("cupons: gerar e escolher código, conferir com mínimo 'acima de', pedido com desconto, limites")
    void coupons() {

        // ── 1. Um código escolhido (15%, acima de R$ 10) e um gerado; código repetido e porcentagem inválida.
        String code = "PROMO" + (System.nanoTime() % 100_000);
        as(owner, Map.of("code", code, "percent", 15, "min_total", 10, "min_inclusive", false))
                .post("/api/coupons").then().statusCode(201);
        JsonPath list = as(owner, Map.of("percent", 10)).post("/api/coupons").then().statusCode(201).extract()
                .jsonPath();
        assertThat(list.getList("code", String.class)).anyMatch(c -> c.matches("RAY-[A-Z2-9]{5}"));
        assertThat(as(owner, Map.of("code", code, "percent", 5)).post("/api/coupons").statusCode()).isEqualTo(409);
        assertThat(as(owner, Map.of("code", "X", "percent", 5)).post("/api/coupons").statusCode()).isEqualTo(400);
        assertThat(as(owner, Map.of("percent", 95)).post("/api/coupons").statusCode()).isEqualTo(400);
        long id = list.getLong("find { it.code == '" + code + "' }.id");
        long generated = list.getLong("find { it.code != '" + code + "' && it.uses == 0 }.id");

        // ── 2. Conferência: 2 itens (R$ 19) passam do mínimo; desconto de R$ 2,85.
        JsonPath ok = as(customer, Map.of("code", code, "items", List.of(Map.of("id", item, "qty", 2))))
                .post("/api/cliente/cupom").then().statusCode(200).extract().jsonPath();
        assertThat(ok.getDouble("discount")).isEqualTo(2.85);
        Response low = as(customer, Map.of("code", code, "items", List.of(Map.of("id", item, "qty", 1))))
                .post("/api/cliente/cupom");
        assertThat(low.jsonPath().getString("error")).isEqualTo("Este cupom vale para pedidos acima de R$ 10,00.");

        // ── 3. Pedido com o cupom: total com desconto; a dona vê o uso e não consegue excluir.
        Map<String, Object> body = order("dinheiro");
        body.put("coupon_code", code.toLowerCase());
        long orderId = as(customer, body).post("/api/cliente/pedidos").then().statusCode(201).extract().jsonPath()
                .getLong("id");
        assertThat(jdbc.sql("SELECT total::float8 FROM shop_orders WHERE id = :id").param("id", orderId)
                .query(Double.class).single()).isEqualTo(16.15);
        assertThat(as(owner, Map.of()).get("/api/coupons").jsonPath().getInt("find { it.id == " + id + " }.uses"))
                .isEqualTo(1);
        assertThat(as(owner, Map.of()).delete("/api/coupons/" + id).statusCode()).isEqualTo(409);

        // ── 4. Cupom inválido no pedido é 400; o que nunca foi usado sai; desconhecido é 404.
        body.put("coupon_code", "NAOEXISTE");
        assertThat(as(customer, body).post("/api/cliente/pedidos").jsonPath().getString("error"))
                .isEqualTo("Cupom inválido.");
        assertThat(as(owner, Map.of()).delete("/api/coupons/" + generated).statusCode()).isEqualTo(200);
        assertThat(as(owner, Map.of("percent", 10)).put("/api/coupons/999999").statusCode()).isEqualTo(404);
    }
}
