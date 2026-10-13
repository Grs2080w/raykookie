package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.payments.application.ConfirmPaymentUseCase;
import br.com.kntap.raykookie.payments.domain.ConfirmationOutcome;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Webhook, confirmação única (concorrência e pagamento em dobro), Pix novo e conciliação dos Pix.
 */
class WebhookAndPixIT extends PaymentsItSupport {

    /** Confirmação única. */
    @Autowired
    ConfirmPaymentUseCase confirm;

    /** Métricas (alertas). */
    @Autowired
    MeterRegistry meters;

    /** Conciliação dos Pix. */
    @Autowired
    @Qualifier("reconcilePendingPaymentsJob")
    ScheduledJob reconcilePending;

    /**
     * Webhook de ponta a ponta.
     */
    @Test
    @DisplayName("webhook: valor menor e pagamento de outro pedido não pagam; aprovado paga uma vez; reenvio idempotente")
    void webhookRules() {

        // ── 1. Valor menor e pagamento que não é do pedido.
        long pix = paymentId();
        long order = pixOrder(pix);
        payment(pix, "approved", String.valueOf(order), 10.0);
        assertThat(webhook(pix, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        long stranger = paymentId();
        payment(stranger, "approved", String.valueOf(order), 19.0);
        webhook(stranger, UUID.randomUUID().toString());
        assertThat(value("SELECT paid_at IS NULL FROM shop_orders WHERE id = :id", order)).isEqualTo(true);

        // ── 2. Aprovado: paga uma vez; reenvio (outro request-id) não repete aviso nem evento.
        payment(pix, "approved", String.valueOf(order), 19.0);
        assertThat(webhook(pix, UUID.randomUUID().toString()).jsonPath().getMap("$")).isEqualTo(Map.of("ok", true));
        webhook(pix, UUID.randomUUID().toString());
        assertThat(value("SELECT count(*) FROM order_events WHERE shop_order_id = :id AND type = 'pago'", order))
                .isEqualTo(1L);
        assertThat(doubles.notices.stream().filter(n -> n instanceof OwnerNotice.Paid p && p.orderId() == order))
                .hasSize(1);

        // ── 3. Mesmo request-id: duplicado (G18); tipo e referência estranhos são ignorados.
        String requestId = UUID.randomUUID().toString();
        webhook(pix, requestId);
        assertThat(webhook(pix, requestId).jsonPath().getBoolean("duplicate")).isTrue();
        long other = paymentId();
        payment(other, "approved", "abc", 1.0);
        assertThat(webhook(other, UUID.randomUUID().toString()).jsonPath().getString("status")).isEqualTo("approved");
        payment(other, "approved", "link:x", 1.0);
        assertThat(webhook(other, UUID.randomUUID().toString()).jsonPath().getString("status")).isEqualTo("approved");
    }

    /**
     * Alerta de fraude, pedido do Mercado Pago e tipos desconhecidos.
     */
    @Test
    @DisplayName("webhook: alerta de fraude registra e avisa a dona; pedido do MP é aplicado; tipo desconhecido só confirma")
    void webhookFraudAndOtherTopics() {

        // ── 1. Alerta de fraude assinado com o payment_id: registra no pedido e avisa a dona (uma vez).
        long pix = paymentId();
        long order = pixOrder(pix);
        Map<String, Object> fraud = Map.of("type", "stop_delivery_op_wh", "action", "stop_delivery_op_wh.created",
                "data", Map.of("merchant_order", "555", "payment_id", pix, "site_id", "MLB"));
        String requestId = UUID.randomUUID().toString();
        Response first = signedWebhook(String.valueOf(pix), requestId, fraud);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.jsonPath().getLong("order")).isEqualTo(order);
        assertThat(value("SELECT count(*) FROM order_events WHERE shop_order_id = :id AND type = 'alerta_fraude'",
                order)).isEqualTo(1L);
        assertThat(doubles.notices.stream().filter(n -> n instanceof OwnerNotice.FraudAlert f && f.orderId() == order))
                .hasSize(1);
        assertThat(signedWebhook(String.valueOf(pix), requestId, fraud).jsonPath().getBoolean("duplicate")).isTrue();

        // ── 2. Alerta sobre pagamento que não é de nenhum pedido: só alerta a operação.
        long stranger = paymentId();
        Map<String, Object> unknown = Map.of("type", "stop_delivery_op_wh", "data", Map.of("payment_id", stranger));
        assertThat(signedWebhook(String.valueOf(stranger), UUID.randomUUID().toString(), unknown).jsonPath()
                .getBoolean("unknown")).isTrue();

        // ── 3. Pedido do Mercado Pago e tipos desconhecidos: 200 sem tratar; assinatura errada continua 401.
        Map<String, Object> mpOrder = Map.of("type", "order", "action", "order.processed", "data", Map.of("id", "ORD1"));
        MP.stubFor(get(urlEqualTo("/v1/orders/ORD1")).willReturn(json("{\"id\":\"ORD1\",\"external_reference\":\"abc\","
                + "\"total_amount\":\"1.00\",\"status\":\"processed\",\"status_detail\":\"accredited\"}")));
        assertThat(signedWebhook("ord1", UUID.randomUUID().toString(), mpOrder).jsonPath().getString("status"))
                .isEqualTo("approved");
        // notificação do modo de teste (segredo de teste): 200 sem tratar, sem consultar o Mercado Pago nem tocar em pedido
        Map<String, Object> testOrder = Map.of("type", "order", "live_mode", false, "data", Map.of("id", "ORDTST9"));
        Response test = signedWebhook(TEST_SECRET, "ordtst9", UUID.randomUUID().toString(), testOrder);
        assertThat(test.statusCode()).isEqualTo(200);
        assertThat(test.jsonPath().getBoolean("test")).isTrue();
        assertThat(signedWebhook("outro-segredo", "ordtst9", UUID.randomUUID().toString(), testOrder).statusCode())
                .isEqualTo(401);

        // id que o Mercado Pago não conhece (o "Simular" do painel, pedido de teste): 200 sem tratar, sem repetir
        MP.stubFor(get(urlEqualTo("/v1/orders/ORDTST404")).willReturn(aResponse().withStatus(404)
                .withBody("{\"errors\":[{\"code\":\"order_not_found\"}]}")));
        Map<String, Object> simulated = Map.of("type", "order", "action", "order.processed", "data",
                Map.of("id", "ORDTST404"));
        Response notFound = signedWebhook("ordtst404", UUID.randomUUID().toString(), simulated);
        assertThat(notFound.statusCode()).isEqualTo(200);
        assertThat(notFound.jsonPath().getString("reason")).isEqualTo("not_found");
        Map<String, Object> merchant = Map.of("type", "topic_merchant_order_wh", "data", Map.of("id", "9"));
        assertThat(signedWebhook("9", UUID.randomUUID().toString(), merchant).jsonPath().getBoolean("ignored"))
                .isTrue();
        Map<String, Object> other = Map.of("type", "topic_chargebacks_wh", "data", Map.of("id", "77"));
        assertThat(signedWebhook("77", UUID.randomUUID().toString(), other).statusCode()).isEqualTo(200);
        assertThat(signedWebhook("outro-id", UUID.randomUUID().toString(), fraud).statusCode()).isEqualTo(401);
    }

    /**
     * Webhook: pedido cancelado, estorno e pendente.
     */
    @Test
    @DisplayName("webhook: pedido cancelado pago devolve; estorno/chargeback concilia; pendente só responde o status")
    void webhookRefunds() {

        // ── 1. Pago depois de cancelado: devolução.
        long pix = paymentId();
        long order = pixOrder(pix);
        jdbc.sql("UPDATE shop_orders SET cancelled_at = now() WHERE id = :id").param("id", order).update();
        payment(pix, "approved", String.valueOf(order), 19.0);
        assertThat(webhook(pix, UUID.randomUUID().toString()).jsonPath().getBoolean("refunded")).isTrue();
        assertThat(doubles.late).containsExactly(order);

        // ── 2. Estorno no cartão e devolução: concilia.
        payment(pix, "charged_back", String.valueOf(order), 19.0);
        assertThat(webhook(pix, UUID.randomUUID().toString()).jsonPath().getString("status")).isEqualTo("charged_back");
        assertThat(doubles.reconciled).containsExactly(order + ":" + pix);
        assertThat(value("SELECT count(*) FROM order_events WHERE shop_order_id = :id AND type = 'estorno_cartao'",
                order)).isEqualTo(1L);
        payment(pix, "pending", String.valueOf(order), 19.0);
        assertThat(webhook(pix, UUID.randomUUID().toString()).jsonPath().getString("status")).isEqualTo("pending");

        // ── 3. Falha ao consultar: 500 para o Mercado Pago tentar de novo.
        long broken = paymentId();
        MP.stubFor(get(urlEqualTo("/v1/payments/" + broken)).willReturn(aResponse().withStatus(500)));
        assertThat(webhook(broken, UUID.randomUUID().toString()).statusCode()).isEqualTo(500);
        assertThat(api().header("x-signature", "x").body("nao-json").post("/api/webhooks/mercadopago").statusCode())
                .isEqualTo(401);
    }

    /**
     * Duas aprovações diferentes ao mesmo tempo (T05, T06).
     *
     * @throws Exception se uma tarefa falhar
     */
    @Test
    @DisplayName("duas aprovações diferentes do mesmo pedido em paralelo: uma paga, a outra é devolvida uma vez")
    void concurrentConfirmations() throws Exception {

        // ── 1. Pedido com dois Pix gerados, os dois aprovados.
        long first = paymentId();
        long second = paymentId();
        long order = pixOrder(first);
        jdbc.sql("UPDATE shop_orders SET mp_payment_ids = array_append(mp_payment_ids, :p) WHERE id = :id")
                .param("p", String.valueOf(second)).param("id", order).update();
        MP.stubFor(post(urlEqualTo("/v1/payments/" + first + "/refunds")).willReturn(json("{\"id\":1,\"amount\":19,"
                + "\"status\":\"approved\"}")));
        MP.stubFor(post(urlEqualTo("/v1/payments/" + second + "/refunds")).willReturn(json("{\"id\":2,\"amount\":19,"
                + "\"status\":\"approved\"}")));
        double alertsBefore = alerts();

        // ── 2. As duas confirmações soltas juntas.
        CountDownLatch go = new CountDownLatch(1);
        Callable<ConfirmationOutcome> a = () -> {
            go.await();
            return confirm.confirm(order, approved(first), null, "pix");
        };
        Callable<ConfirmationOutcome> b = () -> {
            go.await();
            return confirm.confirm(order, approved(second), null, "pix");
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ConfirmationOutcome>> results;
        try {
            List<Future<ConfirmationOutcome>> started = List.of(pool.submit(a), pool.submit(b));
            go.countDown();
            results = started;
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(
                    ConfirmationOutcome.PAID_NOW, ConfirmationOutcome.DUPLICATE_REFUNDED);
        } finally {
            pool.shutdown();
        }

        // ── 3. Uma vira paid_mp_id; a outra volta uma vez, com a chave certa, e gera um alerta.
        String paidWith = (String) value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order);
        String refunded = paidWith.equals(String.valueOf(first)) ? String.valueOf(second) : String.valueOf(first);
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + refunded + "/refunds"))
                .withHeader("X-Idempotency-Key", equalTo("rayka-duplicate-" + refunded)));
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments/" + paidWith + "/refunds")));
        assertThat(alerts()).isGreaterThan(alertsBefore);
        assertThat(confirm.confirm(order, approved(Long.parseLong(paidWith)), null, "pix"))
                .isEqualTo(ConfirmationOutcome.ALREADY_PAID);
    }

    /**
     * Pagamento aprovado de 19 reais.
     *
     * @param id id
     * @return o pagamento
     */
    private static GatewayPayment approved(long id) {
        return new GatewayPayment(String.valueOf(id), "approved", "accredited", null, Money.ofCents(1900), Money.ZERO,
                null, null);
    }

    /**
     * Alertas contados.
     *
     * @return o total
     */
    private double alerts() {
        return meters.find("rayka_alerts").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    /**
     * "Gerar novo Pix".
     */
    @Test
    @DisplayName("novo Pix: válido devolve o mesmo; vencido e pago no último instante vira pago; limite 429; falha 502")
    void regenerate() {

        // ── 1. Ainda válido e vencido pago no último instante.
        long pix = paymentId();
        long order = pixOrder(pix);
        String path = "/api/cliente/pedidos/" + order + "/pix";
        assertThat(as(customer, Map.of()).post(path).jsonPath().getString("state")).isEqualTo("active");
        jdbc.sql("UPDATE shop_orders SET pix_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", order).update();
        payment(pix, "approved", String.valueOf(order), 19.0);
        assertThat(as(customer, Map.of()).post(path).jsonPath().getString("state")).isEqualTo("paid");
        assertThat(as(customer, Map.of()).post(path).jsonPath().getString("state")).isEqualTo("paid");

        // ── 2. Vencido e pendente: desativa e gera outro; falha do MP é 502; limite 429.
        long other = paymentId();
        long second = pixOrder(other);
        jdbc.sql("UPDATE shop_orders SET pix_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", second).update();
        payment(other, "pending", String.valueOf(second), 19.0);
        MP.stubFor(put(urlEqualTo("/v1/payments/" + other)).willReturn(json("{}")));
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/" + second + "/pix").statusCode()).isEqualTo(502);
        MP.verify(putRequestedFor(urlEqualTo("/v1/payments/" + other)));
        long renewed = paymentId();
        nextPix(renewed);
        Response next = as(customer, Map.of()).post("/api/cliente/pedidos/" + second + "/pix");
        assertThat(next.jsonPath().getString("pix.code")).isEqualTo("pix-" + renewed);
        assertThat(value("SELECT pix_attempts FROM shop_orders WHERE id = :id", second)).isEqualTo(2);
        jdbc.sql("UPDATE shop_orders SET pix_expires_at = now() - interval '1 minute', pix_attempts = 10 WHERE id = :id")
                .param("id", second).update();
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/" + second + "/pix").statusCode()).isEqualTo(429);
    }

    /**
     * Regras do "Gerar novo Pix".
     */
    @Test
    @DisplayName("novo Pix: pedido de cartão 400, cancelado 400, de outro cliente 404; Pix falha na criação: pedido sem Pix")
    void regenerateRules() {
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        Response created = as(customer, order("pix")).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getString("pix")).isNull();
        long order = created.jsonPath().getLong("id");
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE shop_order_id = :id").param("id", order).update();
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/" + order + "/pix").statusCode()).isEqualTo(400);
        assertThat(as(signUp(), Map.of()).post("/api/cliente/pedidos/" + order + "/pix").statusCode()).isEqualTo(404);
        jdbc.sql("UPDATE shop_orders SET payment_method = 'cartao' WHERE id = :id").param("id", order).update();
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/" + order + "/pix").statusCode()).isEqualTo(400);
    }

    /**
     * Conciliação dos Pix e cancelamento com Pix pendente.
     */
    @Test
    @DisplayName("conciliação: Pix aprovado com webhook perdido é aplicado; cancelar com Pix pago agora segue como pago")
    void reconcileAndCancel() {

        // ── 1. Conciliação.
        long pix = paymentId();
        long order = pixOrder(pix);
        payment(pix, "approved", String.valueOf(order), 19.0);
        reconcilePending.run();
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order)).isEqualTo(String.valueOf(pix));

        // ── 2. Cancelar com o Pix pendente: desativa no MP e cancela.
        long pending = paymentId();
        long open = pixOrder(pending);
        payment(pending, "pending", String.valueOf(open), 19.0);
        MP.stubFor(put(urlEqualTo("/v1/payments/" + pending)).willReturn(json("{}")));
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/" + open + "/cancelar").jsonPath()
                .getString("state")).isEqualTo("cancelled");
        MP.verify(putRequestedFor(urlEqualTo("/v1/payments/" + pending)));
    }
}
