package br.com.kntap.raykookie.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de devoluções: Mercado Pago falso, a dona, um cliente e pedidos de R$ 100 já pagos
 * (2 brownies de R$ 30 e uma torta de R$ 40), como em {@code backend/tests/refunds.integration.test.ts}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false", "rayka.mp.access-token=TEST-it",
            "rayka.mp.webhook-secret=segredo", "rayka.site-url=https://loja.teste",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
abstract class RefundsItSupport extends PostgresTestBase {

    /** PNG mínimo. */
    static final byte[] PNG = png();

    /** Mercado Pago falso. */
    static final WireMockServer MP = new WireMockServer(wireMockConfig().dynamicPort());

    /** Número das linhas da tela Pedidos. */
    static final AtomicInteger NUMBER = new AtomicInteger(100_000);

    static {
        MP.start();
    }

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Avisos. */
    @Autowired
    NoticeLog log;

    /** A dona. */
    OwnerId owner;

    /** O cliente. */
    CustomerId customer;

    /** Pagamento do último pedido semeado. */
    String lastPayment;

    /**
     * Aponta o Mercado Pago para o falso.
     *
     * @param registry propriedades
     */
    @DynamicPropertySource
    static void mercadoPago(DynamicPropertyRegistry registry) {
        registry.add("rayka.mp.api-base", MP::baseUrl);
    }

    /**
     * PNG com a assinatura e 40 bytes.
     *
     * @return os bytes
     */
    private static byte[] png() {
        byte[] bytes = new byte[48];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, bytes, 0, 8);
        return bytes;
    }

    /**
     * Estado limpo, a dona e um cliente novo; o Mercado Pago aprova toda devolução.
     */
    @BeforeEach
    void setUp() {
        MP.resetAll();
        log.notices.clear();
        jdbc.sql("DELETE FROM rate_limits").update();
        owner = new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
        customer = new CustomerId(jdbc.sql("INSERT INTO customers (email, name) VALUES (:e, 'Maria') RETURNING id")
                .param("e", "dv-" + UUID.randomUUID() + "@x.com").query(Long.class).single());
        refundAnswer(json("{\"id\":900,\"status\":\"approved\",\"amount\":100}"));
    }

    /**
     * O que o Mercado Pago responde a qualquer devolução.
     *
     * @param answer resposta
     */
    static void refundAnswer(ResponseDefinitionBuilder answer) {
        MP.stubFor(post(urlPathMatching("/v1/payments/.*/refunds")).willReturn(answer));
    }

    /**
     * Resposta JSON.
     *
     * @param body corpo
     * @return a resposta
     */
    static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /**
     * Pedido de R$ 100 do cliente, com as duas linhas na situação indicada.
     *
     * @param status    situação das linhas
     * @param paid      se foi pago
     * @param delivered quando foi entregue (nulo = não)
     * @return o id
     */
    long seed(String status, boolean paid, Instant delivered) {
        lastPayment = "pay" + UUID.randomUUID().toString().substring(0, 8);
        long so = jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                       time_window, payment_method, items, total, public_code, paid_at, paid_mp_id, mp_payment_ids,
                       delivered_at)
                VALUES (:customer, :owner, 'Maria', '11999999999', 'retirada', '2026-10-20', 'tarde', 'pix',
                        '[{"id":"1","name":"Brownie","qty":2,"unit_price":30},{"id":"2","name":"Torta","qty":1,"unit_price":40}]',
                        100, 10000 + :n % 80000, CASE WHEN :paid THEN now() END, CASE WHEN :paid THEN :mp END,
                        CASE WHEN :paid THEN ARRAY[CAST(:mp AS text)] ELSE '{}' END, :delivered)
                RETURNING id""")
                .param("customer", customer.value()).param("owner", owner.value()).param("n", NUMBER.get())
                .param("paid", paid).param("mp", lastPayment)
                .param("delivered", delivered == null ? null : java.time.OffsetDateTime.ofInstant(delivered,
                        java.time.ZoneOffset.UTC))
                .query(Long.class).single();
        for (String product : new String[] {"Brownie", "Torta"}) {
            jdbc.sql("""
                    INSERT INTO orders (user_id, number, customer, product, qty, unit_price, status, payment,
                                        shop_order_id)
                    VALUES (:owner, :n, 'Maria', :product, 1, 10, :status, :payment, :so)""")
                    .param("owner", owner.value()).param("n", NUMBER.incrementAndGet()).param("product", product)
                    .param("status", status).param("payment", paid ? "pago" : "pendente").param("so", so).update();
        }
        return so;
    }

    /**
     * Pedido pago, sem entrega.
     *
     * @param status situação das linhas
     * @return o id
     */
    long seed(String status) {
        return seed(status, true, null);
    }

    /**
     * Última devolução do pedido.
     *
     * @param orderId pedido
     * @return as colunas
     */
    Map<String, Object> refundOf(long orderId) {
        return jdbc.sql("""
                SELECT id, kind, status, reason_code, requested_amount::text AS requested, approved_amount::text AS approved,
                       fail_reason, attempts, next_try_at, decision_code, mp_refund_id
                  FROM refunds WHERE shop_order_id = :id ORDER BY id DESC LIMIT 1""").param("id", orderId).query()
                .singleRow();
    }

    /**
     * O pedido.
     *
     * @param orderId pedido
     * @return as colunas
     */
    Map<String, Object> order(long orderId) {
        return jdbc.sql("""
                SELECT refunded_total::text AS refunded, cancelled_at, cancelled_by, cancel_reason_code, cancel_reason,
                       cancel_note
                  FROM shop_orders WHERE id = :id""").param("id", orderId).query().singleRow();
    }

    /**
     * Tipos dos eventos do pedido.
     *
     * @param orderId pedido
     * @return os tipos
     */
    java.util.List<String> events(long orderId) {
        return jdbc.sql("SELECT type FROM order_events WHERE shop_order_id = :id ORDER BY id").param("id", orderId)
                .query(String.class).list();
    }

    /**
     * Requisição pela rede.
     *
     * @return a requisição
     */
    RequestSpecification api() {
        return RestAssured.given().port(port);
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    RequestSpecification json() {
        return api().contentType(ContentType.JSON);
    }
}
