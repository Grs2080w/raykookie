package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

    /** Cartões em análise. */
    @Autowired
    @Qualifier("settleReviewedCardsJob")
    ScheduledJob settleReviewedCards;

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
     * O Mercado Pago responde "em análise" e decide em seguida.
     */
    @Test
    @DisplayName("em análise que vira recusa em segundos: o cliente já recebe a recusa, e a tentativa fica fechada")
    void reviewTurnsIntoRefusal() {

        // ── 1. Análise na criação; recusa por segurança na consulta seguinte.
        long id = paymentId();
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + id
                + ",\"status\":\"in_process\",\"status_detail\":\"pending_review_manual\","
                + "\"transaction_amount\":19.0}")));
        MP.stubFor(get(urlEqualTo("/v1/payments/" + id)).willReturn(json("{\"id\":" + id
                + ",\"status\":\"rejected\",\"status_detail\":\"cc_rejected_high_risk\","
                + "\"transaction_amount\":19.0}")));

        // ── 2. Resposta final e tentativa fechada.
        Response order = freshCardOrder("REVI");
        assertThat(order.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", false,
                "reason", "O Mercado Pago recusou por segurança e nada foi cobrado. Com o mesmo cartão, tentar de novo "
                        + "agora costuma ser recusado também: use outro cartão ou tente mais tarde."));
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id",
                order.jsonPath().getLong("id"))).isEqualTo("rejected");
    }

    /**
     * Tentar de novo com um pagamento ainda em análise.
     */
    @Test
    @DisplayName("de novo com pagamento em análise: 409 sem cobrar; aprovado depois, só confirma o pedido")
    void retryWhileInReview() {

        // ── 1. Pedido com o cartão em análise (a consulta continua em análise).
        magicNames(0);
        long order = freshCardOrder("CONT").jsonPath().getLong("id");
        String gatewayId = (String) value("SELECT gateway_id FROM payment_attempts WHERE shop_order_id = :id", order);
        MP.stubFor(get(urlEqualTo("/v1/payments/" + gatewayId)).willReturn(json("{\"id\":" + gatewayId
                + ",\"status\":\"in_process\",\"status_detail\":\"pending_review_manual\","
                + "\"transaction_amount\":19.0,"
                + "\"external_reference\":\"" + order + "\"}")));

        // ── 2. Nova tentativa barrada, sem chamar a cobrança.
        Response blocked = as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.jsonPath().getString("error")).startsWith("Seu pagamento anterior ainda está em análise");
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token",
                equalTo("APRO"))));

        // ── 3. Aprovado depois: a nova tentativa só confirma o pedido.
        MP.stubFor(get(urlEqualTo("/v1/payments/" + gatewayId)).willReturn(json("{\"id\":" + gatewayId
                + ",\"status\":\"approved\",\"status_detail\":\"accredited\",\"transaction_amount\":19.0,"
                + "\"external_reference\":\"" + order + "\"}")));
        Response confirmed = as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(confirmed.jsonPath().getMap("$")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_at IS NOT NULL FROM shop_orders WHERE id = :id", order)).isEqualTo(true);
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token",
                equalTo("APRO"))));
    }

    /**
     * Corpo enviado ao Mercado Pago.
     */
    @Test
    @DisplayName("corpo do cartão: token, valor, parcelas, bandeira, pagador com CPF, antifraude e device ID, chave")
    void requestBody() {

        // ── 1. Cobra, com o device ID do app.
        magicNames(0);
        Map<String, Object> request = order("cartao");
        request.putAll(card("APRO"));
        request.put("device_id", "armor.dev-123");
        long order = as(customer, request).post("/api/cliente/pedidos").jsonPath().getLong("id");
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
        assertThat(body.getString("payer.last_name")).isEqualTo("Souza");

        // ── 3. Antifraude: item, comprador com telefone, IP, nome na fatura e aparelho.
        assertThat(body.getString("statement_descriptor")).isEqualTo("RAYKOOKIE");
        assertThat(body.getString("additional_info.items[0].id")).isEqualTo(String.valueOf(order));
        assertThat(body.getInt("additional_info.items[0].quantity")).isEqualTo(1);
        assertThat(body.getDouble("additional_info.items[0].unit_price")).isEqualTo(19.0);
        assertThat(body.getString("additional_info.payer.last_name")).isEqualTo("Souza");
        assertThat(body.getString("additional_info.payer.phone.area_code")).isEqualTo("11");
        assertThat(body.getString("additional_info.payer.phone.number")).isEqualTo("912345678");
        assertThat(body.getString("additional_info.ip_address")).isNotBlank();
        assertThat(sent.getHeader("X-meli-session-id")).isEqualTo("armor.dev-123");
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
        magicNames(0); // o Pix acima responde "pending" a qualquer cobrança; o cartão volta aos nomes mágicos
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

    /**
     * A API recusa os dados da cobrança (4xx).
     */
    @Test
    @DisplayName("token vencido (400 do Mercado Pago): recusa com o motivo, sem 502 e sem alerta; tentativa fechada")
    void requestErrorIsRefusal() {

        // ── 1. O Mercado Pago não acha o token.
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"message\":\"Card Token not found\","
                        + "\"status\":400,\"cause\":[{\"code\":2006,\"description\":\"Card Token not found\"}]}")));
        Response order = freshCardOrder("VELHO");

        // ── 2. Recusa com o motivo, tentativa recusada e contada no pedido.
        long id = order.jsonPath().getLong("id");
        assertThat(order.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", false, "reason",
                "Os dados do cartão expiraram. Toque em pagar de novo."));
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", id)).isEqualTo("rejected");
        assertThat(value("SELECT card_attempts FROM shop_orders WHERE id = :id", id)).isEqualTo(1);
    }

    /**
     * Aprovado, mas a confirmação do pedido falha.
     */
    @Test
    @DisplayName("aprovado e a confirmação falha: o cliente ouve aprovado, a tentativa fica em análise e o job confirma")
    void approvedButConfirmationFails() {

        // ── 1. O banco recusa marcar o pedido como pago (falha simulada).
        magicNames(0);
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION it_block_paid() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.paid_at IS NOT NULL THEN RAISE EXCEPTION 'falha simulada'; END IF; RETURN NEW; END $$""")
                .update();
        jdbc.sql("CREATE TRIGGER it_block_paid BEFORE UPDATE ON shop_orders FOR EACH ROW "
                + "EXECUTE FUNCTION it_block_paid()").update();
        Response order;
        try {
            order = freshCardOrder("APRO");
        } finally {
            jdbc.sql("DROP TRIGGER it_block_paid ON shop_orders").update();
        }

        // ── 2. Aprovado para o cliente; pedido ainda sem pagamento; tentativa em análise com o pagamento.
        long id = order.jsonPath().getLong("id");
        assertThat(order.jsonPath().getMap("card")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_at IS NOT NULL FROM shop_orders WHERE id = :id", id)).isEqualTo(false);
        String gatewayId = (String) value("SELECT gateway_id FROM payment_attempts WHERE shop_order_id = :id", id);
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", id)).isEqualTo("pending");

        // ── 3. O job confirma.
        payment(Long.parseLong(gatewayId), "approved", String.valueOf(id), 19.0);
        settleReviewedCards.run();
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", id)).isEqualTo(gatewayId);
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", id)).isEqualTo("approved");
    }

    /**
     * Nova tentativa depois de uma que ficou sem resposta, e o Mercado Pago tinha aprovado.
     */
    @Test
    @DisplayName("sem resposta (500) e a cobrança tinha passado: a nova tentativa só confirma, sem cobrar de novo")
    void retryAfterLostAnswerFindsApproved() {

        // ── 1. Erro do Mercado Pago na primeira cobrança.
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        long order = freshCardOrder("PERDIDO").jsonPath().getLong("id");
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", order)).isEqualTo("error");

        // ── 2. A busca acha o pagamento aprovado: confirma sem chamar a cobrança.
        long approved = paymentId();
        MP.stubFor(get(urlPathEqualTo("/v1/payments/search")).willReturn(json("{\"results\":[{\"id\":" + approved
                + ",\"status\":\"approved\",\"external_reference\":\"" + order + "\",\"transaction_amount\":19.0}]}")));
        Response retry = as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(retry.jsonPath().getMap("$")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order))
                .isEqualTo(String.valueOf(approved));
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token",
                equalTo("APRO"))));
    }

    /**
     * Nova tentativa depois de uma que ficou sem resposta, e nada foi criado.
     */
    @Test
    @DisplayName("sem resposta (500) e nada foi criado: a tentativa antiga fecha e a nova cobra normalmente")
    void retryAfterLostAnswerCharges() {

        // ── 1. Erro na primeira; a busca não acha nada.
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        long order = freshCardOrder("PERDIDO").jsonPath().getLong("id");
        MP.stubFor(get(urlPathEqualTo("/v1/payments/search")).willReturn(json("{\"results\":[]}")));

        // ── 2. A nova cobra e aprova; a antiga fica recusada.
        magicNames(0);
        Response retry = as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(retry.jsonPath().getMap("$")).isEqualTo(Map.of("approved", true));
        assertThat(jdbc.sql("SELECT state FROM payment_attempts WHERE shop_order_id = :id ORDER BY created_at")
                .param("id", order).query(String.class).list()).containsExactly("rejected", "approved");
    }

    /**
     * Tentativa sem resposta que estava em análise no Mercado Pago.
     */
    @Test
    @DisplayName("sem resposta e o pagamento está em análise: o job registra, o pedido mostra card_in_review e o retry espera")
    void lostAnswerInReview() {

        // ── 1. Erro na cobrança; o pagamento existe, em análise.
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        long order = freshCardOrder("PERDIDO").jsonPath().getLong("id");
        long review = paymentId();
        MP.stubFor(get(urlPathEqualTo("/v1/payments/search")).willReturn(json("{\"results\":[{\"id\":" + review
                + ",\"status\":\"in_process\",\"external_reference\":\"" + order + "\",\"transaction_amount\":19.0}]}")));
        jdbc.sql("UPDATE payment_attempts SET created_at = now() - interval '3 minutes' WHERE shop_order_id = :id")
                .param("id", order).update();

        // ── 2. O job registra a análise; o app vê e a nova tentativa espera.
        expireStaleAttempts.run();
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", order)).isEqualTo("pending");
        assertThat(as(customer, Map.of()).get("/api/cliente/pedidos").jsonPath()
                .getBoolean("find { it.id == " + order + " }.card_in_review")).isTrue();
        payment(review, "in_process", String.valueOf(order), 19.0);
        assertThat(as(customer, card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao").statusCode())
                .isEqualTo(409);

        // ── 3. Aprovado depois: o job confirma e a análise some do pedido.
        payment(review, "approved", String.valueOf(order), 19.0);
        settleReviewedCards.run();
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order)).isEqualTo(String.valueOf(review));
        assertThat(as(customer, Map.of()).get("/api/cliente/pedidos").jsonPath()
                .getBoolean("find { it.id == " + order + " }.card_in_review")).isFalse();
    }

    /**
     * Cartão em análise recusado depois, pelo job.
     */
    @Test
    @DisplayName("em análise e recusado depois: o job fecha a tentativa e avisa o cliente com o motivo")
    void reviewRefusedLaterNotifiesCustomer() {

        // ── 1. Pedido com o cartão em análise.
        magicNames(0);
        long order = freshCardOrder("CONT").jsonPath().getLong("id");
        String gatewayId = (String) value("SELECT gateway_id FROM payment_attempts WHERE shop_order_id = :id", order);

        // ── 2. Recusado na conferência do job: tentativa fechada e aviso ao cliente.
        MP.stubFor(get(urlEqualTo("/v1/payments/" + gatewayId)).willReturn(json("{\"id\":" + gatewayId
                + ",\"status\":\"rejected\",\"status_detail\":\"cc_rejected_insufficient_amount\","
                + "\"transaction_amount\":19.0}")));
        settleReviewedCards.run();
        assertThat(value("SELECT state FROM payment_attempts WHERE shop_order_id = :id", order)).isEqualTo("rejected");
        assertThat(doubles.notices).anyMatch(n -> n instanceof CustomerNotice.CardRefused r
                && r.order().id() == order && r.reason().equals("Saldo ou limite insuficiente."));
    }

    /**
     * Os jobs de cartão rodam mesmo com o Node no mesmo banco.
     */
    @Test
    @DisplayName("jobs de cartão rodam com JOBS_ENABLED=false (o Node não conhece payment_attempts)")
    void cardJobsRunAlongsideNode() {
        assertThat(expireStaleAttempts.runsAlongsideNode()).isTrue();
        assertThat(settleReviewedCards.runsAlongsideNode()).isTrue();
    }
}
