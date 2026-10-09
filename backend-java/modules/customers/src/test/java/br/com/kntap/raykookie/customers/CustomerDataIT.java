package br.com.kntap.raykookie.customers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.customers.application.PurgeCustomerSessionsJob;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exportação só com os dados do próprio cliente; exclusão que apaga as sessões; pedido em andamento
 * impede a exclusão; limpeza de sessões vencidas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class CustomerDataIT extends PostgresTestBase {

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Limpeza de sessões. */
    @Autowired
    PurgeCustomerSessionsJob purge;

    /**
     * Entra como um cliente de teste.
     *
     * @param email e-mail
     * @return o token
     */
    private String login(String email) {
        jdbc.sql("DELETE FROM rate_limits").update();
        return RestAssured.given().port(port).header("x-client", "mobile").contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", "senha-de-teste-123")).post("/api/cliente/login")
                .jsonPath().getString("token");
    }

    /**
     * Um pedido da loja do cliente, com uma linha no painel no status dado.
     *
     * @param email  e-mail do cliente
     * @param code   código público
     * @param status status da linha do painel
     */
    private void order(String email, int code, String status) {
        long id = jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                                         time_window, payment_method, items, total, public_code)
                SELECT c.id, u.id, c.name, '11999999999', 'retirada', current_date + 3, 'tarde', 'pix', '[]', 25.50,
                       :code
                  FROM customers c, users u WHERE c.email = :email AND u.username = 'dona' RETURNING id""")
                .param("email", email).param("code", code).query(Long.class).single();
        jdbc.sql("INSERT INTO orders (user_id, number, customer, product, status, shop_order_id) "
                        + "SELECT owner_id, (SELECT COALESCE(max(number), 0) + 1 FROM orders), contact_name, 'Bolo', "
                        + ":status, id FROM shop_orders WHERE id = :id")
                .param("status", status).param("id", id).update();
    }

    /**
     * A exportação traz todos os pedidos do cliente e nenhum de outro.
     */
    @Test
    @DisplayName("exportação: todos os pedidos do cliente e nenhum de outro")
    void exportOnlyOwnOrders() {

        // ── 1. Dois pedidos da Ana e um da Bia.
        order("ana@teste.com", 4101, "entregue");
        order("ana@teste.com", 4102, "entregue");
        order("bia@teste.com", 4103, "entregue");

        // ── 2. A Ana exporta.
        Response export = RestAssured.given().port(port).header("Authorization", "Bearer " + login("ana@teste.com"))
                .get("/api/cliente/me/exportar");

        // ── 3. Só os dela, com total em número como no Node.
        List<Integer> codes = export.jsonPath().getList("orders.code", Integer.class);
        assertThat(codes).contains(4101, 4102).doesNotContain(4103);
        assertThat(export.jsonPath().getDouble("orders[0].total")).isEqualTo(25.5);
        assertThat(export.asString()).contains("\n  \"account\"");
    }

    /**
     * Pedido em andamento impede a exclusão; sem ele, a exclusão apaga todas as sessões.
     */
    @Test
    @DisplayName("exclusão: bloqueada com pedido em andamento; depois apaga todas as sessões")
    void deleteAccount() {

        // ── 1. Cliente novo com um pedido em andamento e duas sessões.
        jdbc.sql("INSERT INTO customers (email, name, password_hash) SELECT 'cleo@teste.com', 'Cleo', password_hash "
                + "FROM customers WHERE email = 'ana@teste.com' ON CONFLICT DO NOTHING").update();
        order("cleo@teste.com", 4201, "novo");
        String first = login("cleo@teste.com");
        String second = login("cleo@teste.com");

        // ── 2. Bloqueada pelo pedido.
        Response blocked = RestAssured.given().port(port).header("Authorization", "Bearer " + first)
                .contentType(ContentType.JSON).body(Map.of("confirm", "EXCLUIR", "password", "senha-de-teste-123"))
                .delete("/api/cliente/me");
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.jsonPath().getString("error")).startsWith("Você tem 1 pedido em andamento.");

        // ── 3. Pedido entregue: exclui, e as duas sessões somem.
        jdbc.sql("UPDATE orders SET status = 'entregue' WHERE shop_order_id IN "
                + "(SELECT id FROM shop_orders WHERE public_code = 4201)").update();
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + first)
                .contentType(ContentType.JSON).body(Map.of("confirm", "EXCLUIR", "password", "senha-de-teste-123"))
                .delete("/api/cliente/me").statusCode()).isEqualTo(200);
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + second)
                .get("/api/cliente/me").statusCode()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT contact_name FROM shop_orders WHERE public_code = 4201").query(String.class)
                .single()).isEqualTo("Cliente excluído");
    }

    /**
     * Limpeza diária das sessões de cliente vencidas.
     */
    @Test
    @DisplayName("customers.purge-expired-sessions apaga só as vencidas")
    void purgeExpiredSessions() {
        jdbc.sql("INSERT INTO customer_sessions (token_hash, customer_id, expires_at) SELECT 'vencida-c', id, "
                + "now() - interval '1 minute' FROM customers WHERE email = 'bia@teste.com'").update();
        jdbc.sql("INSERT INTO customer_sessions (token_hash, customer_id, expires_at) SELECT 'valida-c', id, "
                + "now() + interval '1 day' FROM customers WHERE email = 'bia@teste.com'").update();
        purge.run();
        assertThat(jdbc.sql("SELECT token_hash FROM customer_sessions WHERE token_hash IN ('vencida-c','valida-c')")
                .query(String.class).list()).containsExactly("valida-c");
        assertThat(purge.name()).isEqualTo("customers.purge-expired-sessions");
    }
}
