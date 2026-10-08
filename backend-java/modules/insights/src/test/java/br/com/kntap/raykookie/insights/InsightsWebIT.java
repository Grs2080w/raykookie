package br.com.kntap.raykookie.insights;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.insights.domain.Months;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Clock;
import java.time.LocalDate;
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
 * Painel e metas pela rede, com o Postgres real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class InsightsWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Relógio. */
    @Autowired
    Clock clock;

    /** Token da dona. */
    String owner;

    /** Id da dona. */
    long ownerId;

    /**
     * Sessão da dona.
     */
    @BeforeEach
    void login() {
        owner = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login")
                .jsonPath().getString("token");
        ownerId = jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single();
    }

    /**
     * Linha da tela Pedidos.
     *
     * @param date    data do pedido
     * @param price   preço
     * @param status  situação
     * @param payment pagamento
     * @return o id
     */
    private long order(LocalDate date, String price, String status, String payment) {
        return jdbc.sql("""
                INSERT INTO orders (user_id, number, order_date, customer, product, qty, unit_price, status, payment,
                                    delivery_date)
                VALUES (:u, (SELECT COALESCE(MAX(number), 0) + 1 FROM orders WHERE user_id = :u), :d, :c, 'Bolo', 1,
                        CAST(:p AS numeric), :s, :pay, :d)
                RETURNING id""")
                .param("u", ownerId).param("d", date).param("c", "Cli " + UUID.randomUUID()).param("p", price)
                .param("s", status).param("pay", payment).query(Long.class).single();
    }

    /**
     * Requisição como a dona.
     *
     * @return a requisição
     */
    private io.restassured.specification.RequestSpecification asOwner() {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + owner).contentType(ContentType.JSON);
    }

    /**
     * Mês do realizado e centavos exatos.
     */
    @Test
    @DisplayName("metas: realizado separa os meses; centavos exatos; devolução descontada sem contar duas vezes")
    void monthStats() {

        // ── 1. Um mês passado fixo, com 0,10 + 0,20 (em double daria 0,30000000000000004).
        LocalDate month = LocalDate.parse("2024-03-01");
        order(month.plusDays(30), "0.10", "novo", "pendente");
        order(month.plusDays(5), "0.20", "pronto", "pago");
        order(month.plusMonths(1), "1000", "novo", "pendente");
        order(month.plusDays(2), "500", "cancelado", "pendente");
        Response r = asOwner().get("/api/goals?month=2024-03-01");
        assertThat(r.jsonPath().getString("month")).isEqualTo("2024-03-01");
        assertThat(r.jsonPath().getDouble("stats.revenue")).isEqualTo(0.3);
        assertThat(r.jsonPath().getInt("stats.orders")).isEqualTo(2);
        assertThat(r.jsonPath().getDouble("stats.avg_ticket")).isEqualTo(0.15);

        // ── 2. Devolução de pedido da loja: só o que passa dos itens cancelados.
        long customer = jdbc.sql("INSERT INTO customers (email, name) VALUES (:e, 'C') RETURNING id")
                .param("e", "in-" + UUID.randomUUID() + "@x.com").query(Long.class).single();
        long so = jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                       time_window, payment_method, items, total, public_code, paid_at, refunded_total)
                VALUES (:c, :u, 'C', '1', 'retirada', '2024-03-20', 'tarde', 'pix', '[]', 100,
                        (SELECT 10000 + floor(random() * 89999)::int), now(), 100) RETURNING id""")
                .param("c", customer).param("u", ownerId).query(Long.class).single();
        long brownie = order(month.plusDays(9), "60", "producao", "pago");
        long torta = order(month.plusDays(9), "40", "cancelado", "pago");
        jdbc.sql("UPDATE orders SET shop_order_id = :so WHERE id IN (:a, :b)").param("so", so).param("a", brownie)
                .param("b", torta).update();
        assertThat(asOwner().get("/api/goals?month=2024-03-01").jsonPath().getDouble("stats.revenue")).isEqualTo(0.3);
    }

    /**
     * Gravar a meta.
     */
    @Test
    @DisplayName("PUT metas: grava e devolve; mês inválido 400; valor negativo 400; GET sem mês usa o atual")
    void saveGoal() {
        Response saved = asOwner().body(Map.of("month", "2026-09-01", "revenue", "1.500,50", "orders", 12.6,
                "new_customers", "3", "avg_ticket", 125)).put("/api/goals");
        assertThat(saved.jsonPath().getDouble("revenue")).isEqualTo(1500.5);
        assertThat(saved.jsonPath().getInt("orders")).isEqualTo(13);
        assertThat(asOwner().get("/api/goals?month=2026-09-01").jsonPath().getDouble("goal.revenue"))
                .isEqualTo(1500.5);
        assertThat(asOwner().body(Map.of("month", "2026-09-02")).put("/api/goals").jsonPath().getString("error"))
                .isEqualTo("Mês inválido.");
        assertThat(asOwner().body(Map.of("month", "2026-09-01", "revenue", -1)).put("/api/goals").statusCode())
                .isEqualTo(400);
        assertThat(asOwner().get("/api/goals?month=lixo").jsonPath().getString("month"))
                .isEqualTo(Months.current(clock).toString());
        assertThat(asOwner().get("/api/goals?month=2020-01-01").jsonPath().getInt("goal.orders")).isZero();
    }

    /**
     * Painel.
     */
    @Test
    @DisplayName("painel: a receber, próximas entregas, estoque baixo, melhores receitas e devoluções")
    void dashboard() {

        // ── 1. Pedidos do mês atual e um ingrediente acabando.
        LocalDate today = Months.current(clock);
        order(today, "10.10", "novo", "pendente");
        order(today, "5", "entregue", "pendente");
        order(today, "7", "novo", "pago");
        jdbc.sql("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost, stock_qty, min_stock) "
                + "VALUES (:u, :n, 'g', 1000, 10, 0, 100)").param("u", ownerId).param("n", "Açúcar " + UUID.randomUUID())
                .update();

        // ── 2. O painel.
        Response d = asOwner().get("/api/dashboard");
        assertThat(d.statusCode()).isEqualTo(200);
        assertThat(d.jsonPath().getString("username")).isEqualTo("dona");
        assertThat(d.jsonPath().getString("month")).isEqualTo(today.toString());
        assertThat(d.jsonPath().getDouble("receivable")).isGreaterThanOrEqualTo(15.1);
        assertThat(d.jsonPath().getList("upcoming.status")).doesNotContain("entregue", "cancelado");
        assertThat(d.jsonPath().getList("low")).isNotEmpty();
        assertThat(d.jsonPath().getBoolean("empty")).isFalse();
        assertThat(d.jsonPath().getInt("refundsWaiting")).isGreaterThanOrEqualTo(0);
        assertThat(d.jsonPath().getMap("stats")).containsKeys("revenue", "profit", "orders", "avg_ticket",
                "new_customers");
        assertThat(RestAssured.given().port(port).get("/api/dashboard").statusCode()).isEqualTo(401);
    }
}
