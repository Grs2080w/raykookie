package br.com.kntap.raykookie.orders;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base dos testes pela rede: sessão da dona, um cliente novo e uma receita a R$ 9,50 no cardápio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
abstract class OrdersItSupport extends PostgresTestBase {


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
    RequestSpecification api() {
        return RestAssured.given().port(port).contentType(ContentType.JSON);
    }

    /**
     * Requisição com sessão.
     *
     * @param token token
     * @param body  corpo
     * @return a requisição
     */
    RequestSpecification as(String token, Object body) {
        return api().header("Authorization", "Bearer " + token).body(body);
    }

    /**
     * Pedido válido para depois de amanhã.
     *
     * @param payment forma de pagamento
     * @return o corpo
     */
    Map<String, Object> order(String payment) {
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
    Response create(String payment) {
        return as(customer, order(payment)).post("/api/cliente/pedidos");
    }

    /**
     * Cliente do pedido.
     *
     * @param orderId pedido da loja
     * @return o cliente
     */
    CustomerId customerOf(long orderId) {
        return new CustomerId(jdbc.sql("SELECT customer_id FROM shop_orders WHERE id = :id").param("id", orderId)
                .query(Long.class).single());
    }
}
