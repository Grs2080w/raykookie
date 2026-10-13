package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import br.com.kntap.raykookie.payments.domain.WebhookSignature;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes pela rede: Mercado Pago falso (WireMock), sessão da dona, um cliente novo e uma receita a
 * R$ 9,50 no cardápio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false", "rayka.mp.access-token=TEST-it",
            "rayka.mp.webhook-secret=" + PaymentsItSupport.SECRET,
            "rayka.mp.webhook-secret-test=" + PaymentsItSupport.TEST_SECRET, "rayka.site-url=https://loja.teste",
            "rayka.mp.review-poll=PT0S",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
abstract class PaymentsItSupport extends PostgresTestBase {

    /** Segredo do webhook. */
    static final String SECRET = "segredo-it";

    /** Segredo do modo de teste do webhook usado nos testes. */
    static final String TEST_SECRET = "segredo-teste-it";

    /** CPF válido de teste. */
    static final String CPF = "529.982.247-25";

    /** Mercado Pago falso, um só para todos os testes (o contexto do Spring é reaproveitado). */
    static final WireMockServer MP = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        MP.start();
    }

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Devoluções de mentira e avisos. */
    @Autowired
    TestDoubles doubles;

    /** Token da dona. */
    String owner;

    /** Token do cliente. */
    String customer;

    /** Item do cardápio. */
    String item;

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
     * Limpa o falso e os limites, abre as sessões e põe uma receita no cardápio.
     */
    @BeforeEach
    void setUp() {

        // ── 1. Estado limpo.
        MP.resetAll();
        doubles.reset();
        jdbc.sql("DELETE FROM rate_limits").update();

        // ── 2. Sessões.
        owner = api().header("x-client", "mobile").body(Map.of("username", "dona", "password", "senha-de-teste-123"))
                .post("/api/auth/login").jsonPath().getString("token");
        customer = signUp();

        // ── 3. Receita a R$ 9,50.
        long recipe = jdbc.sql("""
                INSERT INTO recipes (user_id, name, sale_price)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 9.5) RETURNING id""")
                .param("name", "Cookie pago " + UUID.randomUUID().toString().substring(0, 6)).query(Long.class)
                .single();
        long flour = jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 'g', 1000, 10) RETURNING id""")
                .param("name", "Farinha " + recipe).query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 50)")
                .param("r", recipe).param("i", flour).update();
        item = String.valueOf(recipe);
    }

    /**
     * Cadastra um cliente.
     *
     * @return o token
     */
    String signUp() {
        return api().header("x-client", "mobile").body(Map.of("name", "Maria Souza", "email",
                "pg-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro").jsonPath().getString("token");
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
     * Pedido para depois de amanhã: 2 unidades (R$ 19,00).
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
     * Pedido com cartão.
     *
     * @param token token do cartão (o falso decide pelo token)
     * @return a resposta
     */
    Response cardOrder(String token) {
        Map<String, Object> body = order("cartao");
        body.putAll(card(token));
        return as(customer, body).post("/api/cliente/pedidos");
    }

    /**
     * Dados do cartão.
     *
     * @param token token
     * @return os campos
     */
    static Map<String, Object> card(String token) {
        return Map.of("card_token", token, "card_payment_method_id", "master", "cpf", CPF, "installments", 1,
                "card_issuer_id", "24");
    }

    /**
     * Pedido com Pix (o falso devolve o Pix com este id).
     *
     * @param pix id do Pix
     * @return o id do pedido
     */
    long pixOrder(long pix) {
        nextPix(pix);
        return as(customer, order("pix")).post("/api/cliente/pedidos").jsonPath().getLong("id");
    }

    /**
     * Id de pagamento novo.
     *
     * @return o id
     */
    static long paymentId() {
        return ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    /**
     * O próximo Pix criado tem este id.
     *
     * @param id id
     */
    static void nextPix(long id) {
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + id + ",\"status\":\"pending\","
                + "\"point_of_interaction\":{\"transaction_data\":{\"qr_code\":\"pix-" + id + "\","
                + "\"qr_code_base64\":\"iVBOR\"}}}")));
    }

    /**
     * A consulta do pagamento devolve isto.
     *
     * @param id        pagamento
     * @param status    status
     * @param reference {@code external_reference}
     * @param amount    valor
     */
    static void payment(long id, String status, String reference, double amount) {
        MP.stubFor(get(urlEqualTo("/v1/payments/" + id)).willReturn(json("{\"id\":" + id + ",\"status\":\""
                + status + "\",\"external_reference\":\"" + reference + "\",\"transaction_amount\":" + amount + "}")));
    }

    /**
     * Resposta JSON.
     *
     * @param body corpo
     * @return a resposta
     */
    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /**
     * Notificação assinada agora.
     *
     * @param id        pagamento
     * @param requestId {@code x-request-id}
     * @return a resposta
     */
    Response webhook(long id, String requestId) {
        long ts = Instant.now().getEpochSecond();
        String v1 = WebhookSignature.hmac(SECRET, "id:" + id + ";request-id:" + requestId + ";ts:" + ts + ";");
        return api().header("x-signature", "ts=" + ts + ",v1=" + v1).header("x-request-id", requestId)
                .body(Map.of("type", "payment", "data", Map.of("id", id))).post("/api/webhooks/mercadopago");
    }

    /**
     * Notificação de qualquer tipo, assinada agora com o id informado (o alerta de fraude não traz {@code data.id}).
     *
     * @param signedId  id que entra no texto assinado
     * @param requestId {@code x-request-id}
     * @param body      corpo da notificação
     * @return a resposta
     */
    Response signedWebhook(String signedId, String requestId, Map<String, Object> body) {
        return signedWebhook(SECRET, signedId, requestId, body);
    }

    /**
     * Notificação assinada agora com o segredo informado.
     *
     * @param secret    segredo da assinatura
     * @param signedId  id que entra no texto assinado
     * @param requestId {@code x-request-id}
     * @param body      corpo da notificação
     * @return a resposta
     */
    Response signedWebhook(String secret, String signedId, String requestId, Map<String, Object> body) {
        long ts = Instant.now().getEpochSecond();
        String v1 = WebhookSignature.hmac(secret, "id:" + signedId + ";request-id:" + requestId + ";ts:" + ts + ";");
        return api().header("x-signature", "ts=" + ts + ",v1=" + v1).header("x-request-id", requestId).body(body)
                .post("/api/webhooks/mercadopago");
    }

    /**
     * Valor de uma coluna.
     *
     * @param sql consulta com {@code :id}
     * @param id  id
     * @return o valor
     */
    Object value(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query().singleValue();
    }
}
