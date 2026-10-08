package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sem os módulos de pagamento e devolução: o pedido sai sem cobrança e o que depende de devolver dinheiro é 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false", "test.fake-ports=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class WithoutPortsIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Sem as portas.
     */
    @Test
    @DisplayName("sem pagamentos: Pix nulo e cartão indisponível; sem devoluções: cancelar pedido pago é 503")
    void fallbacks() {

        // ── 1. Sessões e uma receita.
        jdbc.sql("DELETE FROM rate_limits").update();
        String owner = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login").jsonPath()
                .getString("token");
        String customer = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("name", "Sem Portas", "email", "sp-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@teste.com", "password", "senha-boa-123")).post("/api/cliente/cadastro").jsonPath()
                .getString("token");
        long recipe = jdbc.sql("INSERT INTO recipes (user_id, name, sale_price) VALUES ((SELECT id FROM users WHERE "
                + "username = 'dona'), :n, 200) RETURNING id").param("n", "Torta " + UUID.randomUUID())
                .query(Long.class).single();
        long flour = jdbc.sql("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost) VALUES "
                + "((SELECT id FROM users WHERE username = 'dona'), :n, 'g', 1000, 10) RETURNING id")
                .param("n", "Farinha " + recipe).query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 50)").param("r", recipe)
                .param("i", flour).update();

        // ── 2. Pix e cartão sem o módulo de pagamentos.
        Map<String, Object> pix = order(recipe, "pix");
        Response created = RestAssured.given().port(port).contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + customer).body(pix).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getString("pix")).isNull();
        Map<String, Object> card = new java.util.HashMap<>(order(recipe, "cartao"));
        card.putAll(Map.of("card_token", "tok", "card_payment_method_id", "visa", "cpf", "529.982.247-25",
                "installments", 2, "card_issuer_id", "25"));
        Response byCard = RestAssured.given().port(port).contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + customer).body(card).post("/api/cliente/pedidos");
        assertThat(byCard.jsonPath().getBoolean("card.approved")).isFalse();
        assertThat(byCard.jsonPath().getString("card.reason")).isEqualTo("Pagamento com cartão indisponível no momento.");

        // ── 3. Pedido pago: cancelar precisa do módulo de devoluções.
        long id = created.jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET paid_at = now() WHERE id = :id").param("id", id).update();
        assertThat(RestAssured.given().port(port).contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + customer).body("{}").post("/api/cliente/pedidos/" + id
                        + "/cancelar").statusCode()).isEqualTo(503);
        assertThat(RestAssured.given().port(port).contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + owner).body("{}").post("/api/orders/shop/" + id + "/cancelar")
                .statusCode()).isEqualTo(503);
    }

    /**
     * Pedido para depois de amanhã.
     *
     * @param recipe  receita
     * @param payment forma de pagamento
     * @return o corpo
     */
    private static Map<String, Object> order(long recipe, String payment) {
        return Map.of("items", List.of(Map.of("id", String.valueOf(recipe), "qty", 1)), "contact_name", "Cliente",
                "phone", "11912345678", "fulfillment", "retirada", "scheduled_for",
                LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString(), "time_window", "noite",
                "payment_method", payment);
    }
}
