package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tela Pedidos da dona pela rede: pedido à mão e as regras das linhas de pedido do app.
 */
class ManualOrdersWebIT extends OrdersItSupport {

    /**
     * Pedido à mão.
     *
     * @return o corpo
     */
    private static Map<String, Object> manual() {
        Map<String, Object> body = new HashMap<>();
        body.put("customer", "Ana");
        body.put("product", "Bolo de cenoura");
        body.put("qty", "2");
        body.put("unit_price", "45,50");
        body.put("delivery_date", "2026-12-20");
        body.put("notes", "sem cobertura");
        return body;
    }

    /**
     * Linha da tela Pedidos de um pedido da loja.
     *
     * @return o id da linha
     */
    private long appLine() {
        long id = create("dinheiro").jsonPath().getLong("id");
        return jdbc.sql("SELECT id FROM orders WHERE shop_order_id = :id").param("id", id).query(Long.class).single();
    }

    /**
     * Pedido à mão de ponta a ponta.
     */
    @Test
    @DisplayName("pedido à mão: cria com o número seguinte, lista, troca rápida, edição completa e exclusão")
    void manualOrder() {

        // ── 1. Cria e lista.
        Response created = as(owner, manual()).post("/api/orders");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getDouble("unit_price")).isEqualTo(45.5);
        long id = created.jsonPath().getLong("id");
        Response list = api().header("Authorization", "Bearer " + owner).get("/api/orders");
        assertThat(list.jsonPath().getList("id", Long.class)).contains(id);

        // ── 2. Troca rápida e edição completa.
        assertThat(as(owner, Map.of("payment", "sinal")).patch("/api/orders/" + id).jsonPath().getString("payment"))
                .isEqualTo("sinal");
        Map<String, Object> edited = manual();
        edited.put("status", "pronto");
        edited.put("customer", "Ana Paula");
        assertThat(as(owner, edited).patch("/api/orders/" + id).jsonPath().getString("customer"))
                .isEqualTo("Ana Paula");

        // ── 3. Exclui; depois é 404.
        assertThat(api().header("Authorization", "Bearer " + owner).delete("/api/orders/" + id).jsonPath()
                .getBoolean("ok")).isTrue();
        assertThat(as(owner, Map.of("payment", "pago")).patch("/api/orders/" + id).statusCode()).isEqualTo(404);
        assertThat(as(owner, manual()).patch("/api/orders/" + id).statusCode()).isEqualTo(404);
        assertThat(as(owner, Map.of("customer", "")).post("/api/orders").statusCode()).isEqualTo(400);
    }

    /**
     * Linha de pedido do app.
     */
    @Test
    @DisplayName("linha do app: troca rápida vale, cancelar e mudar item/valor é 409, não é excluída")
    void appLineRules() {

        // ── 1. Troca rápida e cancelamento.
        long line = appLine();
        assertThat(as(owner, Map.of("status", "pronto")).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        assertThat(as(owner, Map.of("status", "cancelado")).patch("/api/orders/" + line).statusCode())
                .isEqualTo(409);

        // ── 2. Edição completa: só o que não é item nem valor.
        Map<String, Object> full = new HashMap<>(Map.of("customer", "Maria Souza", "product",
                jdbc.sql("SELECT product FROM orders WHERE id = :id").param("id", line).query(String.class).single(),
                "qty", 2, "unit_price", "9,50", "status", "pronto", "payment", "pago", "notes", "buscar às 15h"));
        assertThat(as(owner, full).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        full.put("status", "cancelado");
        assertThat(as(owner, full).patch("/api/orders/" + line).statusCode()).isEqualTo(409);
        full.put("status", "pronto");
        full.put("qty", 3);
        assertThat(as(owner, full).patch("/api/orders/" + line).statusCode()).isEqualTo(409);

        // ── 3. Não some; encerrado não muda mais.
        assertThat(api().header("Authorization", "Bearer " + owner).delete("/api/orders/" + line).statusCode())
                .isEqualTo(409);
        jdbc.sql("UPDATE shop_orders SET cancelled_at = now() WHERE id = (SELECT shop_order_id FROM orders "
                + "WHERE id = :id)").param("id", line).update();
        assertThat(as(owner, Map.of("status", "novo")).patch("/api/orders/" + line).statusCode()).isEqualTo(409);
    }

    /**
     * Linha de outra conta e corrida com a promoção.
     */
    @Test
    @DisplayName("outra conta: 404; linha que virou do app entre a leitura e a escrita: 409 ao cancelar")
    void otherAccountAndRace() {
        long other = jdbc.sql("INSERT INTO users (username, password_hash) VALUES (:u, 'x') RETURNING id")
                .param("u", "outra-" + UUID.randomUUID().toString().substring(0, 8)).query(Long.class).single();
        long id = jdbc.sql("INSERT INTO orders (user_id, number, customer, product) VALUES (:o, 1, 'X', 'Y') "
                + "RETURNING id").param("o", other).query(Long.class).single();
        assertThat(as(owner, Map.of("payment", "pago")).patch("/api/orders/" + id).statusCode()).isEqualTo(404);
        api().header("Authorization", "Bearer " + owner).delete("/api/orders/" + id);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(1);
    }
}
