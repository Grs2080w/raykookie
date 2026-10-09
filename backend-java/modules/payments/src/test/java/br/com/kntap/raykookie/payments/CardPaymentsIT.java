package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Cartão de ponta a ponta pela rota HTTP, com o Mercado Pago falso decidindo pelo token (os nomes mágicos do
 * sandbox; respostas escritas à mão até as gravações da Tarefa 3.1).
 */
class CardPaymentsIT extends PaymentsItSupport {

    /** Nomes mágicos e o que o Mercado Pago responde. */
    private static final Map<String, String[]> MAGIC = Map.of(
            "APRO", new String[] {"approved", "accredited"},
            "CONT", new String[] {"in_process", "pending_contingency"},
            "OTHE", new String[] {"rejected", "cc_rejected_other_reason"},
            "CALL", new String[] {"rejected", "cc_rejected_call_for_authorize"},
            "FUND", new String[] {"rejected", "cc_rejected_insufficient_amount"},
            "SECU", new String[] {"rejected", "cc_rejected_bad_filled_security_code"},
            "EXPI", new String[] {"rejected", "cc_rejected_bad_filled_date"},
            "FORM", new String[] {"rejected", "cc_rejected_bad_filled_other"});

    /** Mensagens esperadas de cada recusa. */
    private static final Map<String, String> REASONS = Map.of(
            "OTHE", "Não conseguimos aprovar esse cartão. Tente outro cartão ou Pix.",
            "CALL", "O banco pediu para autorizar essa compra antes. Fale com o banco ou tente outro cartão.",
            "FUND", "Saldo ou limite insuficiente.",
            "SECU", "O código de segurança (CVV) está errado.",
            "EXPI", "A validade do cartão está errada.",
            "FORM", "Confira os dados do cartão e tente de novo.");

    /** Tentativas sem resposta. */
    @Autowired
    @Qualifier("expireStaleAttemptsJob")
    ScheduledJob expireStaleAttempts;

    /**
     * O falso responde cada nome mágico (e um token lento com atraso).
     *
     * @param delayMs atraso do token {@code LENTO}
     */
    private static void magicNames(int delayMs) {
        MAGIC.forEach((token, answer) -> MP.stubFor(post(urlEqualTo("/v1/payments"))
                .withRequestBody(matchingJsonPath("$.token", equalTo(token)))
                .willReturn(json("{\"id\":" + paymentId() + ",\"status\":\"" + answer[0] + "\",\"status_detail\":\""
                        + answer[1] + "\",\"transaction_amount\":19.0}"))));
        MP.stubFor(post(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token", equalTo("LENTO")))
                .willReturn(json("{\"id\":" + paymentId() + ",\"status\":\"approved\",\"status_detail\":\"accredited\","
                        + "\"transaction_amount\":19.0}").withFixedDelay(delayMs)));
    }

    /**
     * Um pedido de cartão com cliente novo (o limite de pedidos sem pagar é por cliente).
     *
     * @param token nome mágico
     * @return a resposta
     */
    private Response freshCardOrder(String token) {
        jdbc.sql("DELETE FROM rate_limits").update();
        customer = signUp();
        return cardOrder(token);
    }

    /**
     * Nomes mágicos.
     */
    @Test
    @DisplayName("nomes mágicos: APRO paga e avisa; CONT fica pendente; cada recusa com a mensagem em português")
    void magicNamesEndToEnd() {

        // ── 1. Aprovado: pago, com aviso à dona e ao cliente.
        magicNames(0);
        Response apro = freshCardOrder("APRO");
        long paid = apro.jsonPath().getLong("id");
        assertThat(apro.jsonPath().getMap("card")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_at IS NOT NULL FROM shop_orders WHERE id = :id", paid)).isEqualTo(true);
        assertThat(doubles.notices).anyMatch(n -> n instanceof OwnerNotice.Paid p && p.orderId() == paid);
        assertThat(doubles.notices).anyMatch(n -> n instanceof CustomerNotice.Paid p && p.order().id() == paid);

        // ── 2. Em análise: pendente, sem pagar.
        Response cont = freshCardOrder("CONT");
        assertThat(cont.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", true));
        assertThat(value("SELECT card_attempts FROM shop_orders WHERE id = :id", cont.jsonPath().getLong("id")))
                .isEqualTo(1);

        // ── 3. Recusas: o pedido existe, sem pagamento, com a mensagem certa.
        REASONS.forEach((token, reason) -> {
            Response refused = freshCardOrder(token);
            assertThat(refused.statusCode()).isEqualTo(201);
            assertThat(refused.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", false,
                    "reason", reason));
        });
    }

    /**
     * Corpo enviado ao Mercado Pago.
     */
    @Test
    @DisplayName("corpo do cartão: token, valor com 2 casas, parcelas, bandeira, emissor, pagador com CPF, chave UUID")
    void requestBody() {

        // ── 1. Cobra.
        magicNames(0);
        long order = cardOrder("APRO").jsonPath().getLong("id");
        LoggedRequest sent = MP.findAll(postRequestedFor(urlEqualTo("/v1/payments"))).getFirst();
        JsonPath body = new JsonPath(sent.getBodyAsString());

        // ── 2. Campos.
        assertThat(sent.getBodyAsString()).contains("\"transaction_amount\":19.00");
        assertThat(body.getString("token")).isEqualTo("APRO");
        assertThat(body.getInt("installments")).isEqualTo(1);
        assertThat(body.getString("payment_method_id")).isEqualTo("master");
        assertThat(body.getString("issuer_id")).isEqualTo("24");
        assertThat(body.getString("payer.first_name")).isEqualTo("Maria");
        assertThat(body.getString("payer.email")).startsWith("pg-");
        assertThat(body.getString("payer.identification.type")).isEqualTo("CPF");
        assertThat(body.getString("payer.identification.number")).isEqualTo("52998224725");
        assertThat(body.getString("external_reference")).isEqualTo(String.valueOf(order));
        assertThat(body.getString("notification_url")).isEqualTo("https://loja.teste/api/webhooks/mercadopago");
        assertThat(sent.getHeader("X-Idempotency-Key")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
                + "[0-9a-f]{12}");
    }

    /**
     * Duas tentativas ao mesmo tempo (G03).
     *
     * @throws Exception se uma chamada falhar
     */
    @Test
    @DisplayName("G03: duas cobranças simultâneas do mesmo pedido: uma processa, a outra 409; uma só cobrança")
    void concurrentAttempts() throws Exception {

        // ── 1. Pedido recusado e duas tentativas lentas juntas.
        magicNames(1500);
        long order = cardOrder("FUND").jsonPath().getLong("id");
        Callable<Integer> retry = () -> as(customer, card("LENTO")).post("/api/cliente/pedidos/" + order + "/cartao")
                .statusCode();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results;
        try {
            results = pool.invokeAll(List.of(retry, retry));
        } finally {
            pool.shutdown();
        }

        // ── 2. Uma passou, a outra esbarrou na tentativa em andamento.
        assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token",
                equalTo("LENTO"))));
    }

    /**
     * Cartão aprovado em pedido cancelado no meio do caminho.
     */
    @Test
    @DisplayName("pedido cancelado e cartão aprovado: fica pago e a devolução integral é pedida; resposta approved")
    void cardOnCancelledOrder() {
        magicNames(0);
        long order = cardOrder("FUND").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'loja' WHERE id = :id")
                .param("id", order).update();
        Response approved = as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(approved.jsonPath().getMap("$")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_at IS NOT NULL FROM shop_orders WHERE id = :id", order)).isEqualTo(true);
        assertThat(doubles.late).containsExactly(order);
        assertThat(doubles.notices).noneMatch(n -> n instanceof OwnerNotice.Paid p && p.orderId() == order);
    }

    /**
     * Limite de recusas (G04).
     */
    @Test
    @DisplayName("G04: 5 recusas do mesmo cliente em pedidos diferentes: a 6ª tentativa é 429 sem chamar o gateway")
    void refusalLimit() {
        magicNames(0);
        long[] orders = {cardOrder("FUND").jsonPath().getLong("id"), cardOrder("FUND").jsonPath().getLong("id"),
            cardOrder("FUND").jsonPath().getLong("id")};
        assertThat(as(customer, card("FUND")).post("/api/cliente/pedidos/" + orders[0] + "/cartao").statusCode())
                .isEqualTo(200);
        assertThat(as(customer, card("FUND")).post("/api/cliente/pedidos/" + orders[1] + "/cartao").statusCode())
                .isEqualTo(200);
        Response blocked = as(customer, card("APRO")).post("/api/cliente/pedidos/" + orders[2] + "/cartao");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.jsonPath().getString("error"))
                .isEqualTo("Muitas tentativas de cartão. Tente pagar com Pix ou fale com a gente.");
        MP.verify(5, postRequestedFor(urlEqualTo("/v1/payments")));
    }

    /**
     * Regras do "Tentar outro cartão".
     */
    @Test
    @DisplayName("tentar outro cartão: pago é approved; Pix 400; de outro cliente 404; 5 tentativas 429; desligado 503")
    void retryRules() {

        // ── 1. Pago, de Pix e de outro cliente.
        magicNames(0);
        long paid = cardOrder("APRO").jsonPath().getLong("id");
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/" + paid + "/cartao").jsonPath()
                .getBoolean("approved")).isTrue();
        long pix = pixOrder(paymentId());
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/" + pix + "/cartao").statusCode())
                .isEqualTo(400);
        assertThat(as(signUp(), card("APRO")).post("/api/cliente/pedidos/" + paid + "/cartao").statusCode())
                .isEqualTo(404);
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/x/cartao").statusCode()).isEqualTo(404);

        // ── 2. Limite por pedido, pedido cancelado e cartão desligado.
        long refused = cardOrder("FUND").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET card_attempts = 5 WHERE id = :id").param("id", refused).update();
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/" + refused + "/cartao").statusCode())
                .isEqualTo(429);
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE shop_order_id = :id").param("id", refused).update();
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/" + refused + "/cartao").statusCode())
                .isEqualTo(400);
        doubles.cardEnabled = false;
        assertThat(cardOrder("APRO").jsonPath().getString("card.reason"))
                .isEqualTo("O pagamento com cartão não está disponível agora.");
    }

    /**
     * Mercado Pago lento (T09).
     */
    @Test
    @DisplayName("Mercado Pago lento: 502 em até 11 s, tentativa com erro; o job aplica depois a cobrança aprovada")
    void slowGateway() {

        // ── 1. Lento: 502 dentro do tempo limite.
        magicNames(15_000);
        long order = cardOrder("FUND").jsonPath().getLong("id");
        Instant start = Instant.now();
        Response slow = as(customer, card("LENTO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(Duration.between(start, Instant.now())).isLessThanOrEqualTo(Duration.ofSeconds(11));
        assertThat(slow.statusCode()).isEqualTo(502);
        assertThat(slow.jsonPath().getString("error"))
                .isEqualTo("Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague com Pix.");
        assertThat(jdbc.sql("SELECT state FROM payment_attempts WHERE shop_order_id = :id ORDER BY created_at DESC "
                + "LIMIT 1").param("id", order).query(String.class).single()).isEqualTo("error");

        // ── 2. A cobrança tinha sido aprovada: o job encontra e aplica.
        long approved = paymentId();
        MP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/v1/payments/search"))
                .willReturn(json("{\"results\":[{\"id\":" + approved + ",\"status\":\"approved\","
                        + "\"external_reference\":\"" + order + "\",\"transaction_amount\":19.0}]}")));
        jdbc.sql("UPDATE payment_attempts SET created_at = now() - interval '3 minutes' WHERE shop_order_id = :id")
                .param("id", order).update();
        expireStaleAttempts.run();
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order)).isEqualTo(String.valueOf(approved));
    }
}
