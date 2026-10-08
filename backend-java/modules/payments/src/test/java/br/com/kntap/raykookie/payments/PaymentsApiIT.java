package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.persistence.AfterCommit;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * O que pagamentos oferece às devoluções, o adaptador do Mercado Pago e as tarefas agendadas.
 */
class PaymentsApiIT extends PaymentsItSupport {

    /** API de pagamentos. */
    @Autowired
    PaymentsApi api;

    /** Gateway. */
    @Autowired
    PaymentGateway gateway;

    /** Configuração. */
    @Autowired
    PaymentSettings settings;

    /** Limpeza das entregas de webhook. */
    @Autowired
    @Qualifier("purgeWebhookDeliveriesJob")
    ScheduledJob purgeDeliveries;

    /** Conciliação dos links. */
    @Autowired
    @Qualifier("reconcileLinksJob")
    ScheduledJob reconcileLinks;

    /** Tentativas sem resposta. */
    @Autowired
    @Qualifier("expireStaleAttemptsJob")
    ScheduledJob expireStaleAttempts;

    /**
     * Devoluções pelo gateway.
     */
    @Test
    @DisplayName("devolução: total sem valor, parcial com valor e chave; lista; erro vira GatewayException")
    void refunds() {

        // ── 1. Total e parcial.
        MP.stubFor(post(urlEqualTo("/v1/payments/55/refunds")).willReturn(json("{\"id\":7,\"amount\":12.5,"
                + "\"status\":\"approved\"}")));
        GatewayRefund whole = api.refund("55", null, "k1");
        assertThat(whole).isEqualTo(new GatewayRefund("7", Money.ofCents(1250), "approved"));
        assertThat(whole.approved()).isTrue();
        api.refund("55", Money.ofCents(500), "k2");
        MP.verify(postRequestedFor(urlEqualTo("/v1/payments/55/refunds")).withHeader("X-Idempotency-Key",
                equalTo("k1")).withRequestBody(equalToJson("{}")));
        MP.verify(postRequestedFor(urlEqualTo("/v1/payments/55/refunds")).withHeader("X-Idempotency-Key",
                equalTo("k2")).withRequestBody(equalToJson("{\"amount\":5.00}")));

        // ── 2. Lista e erros.
        MP.stubFor(get(urlEqualTo("/v1/payments/55/refunds")).willReturn(json("[{\"id\":7,\"amount\":12.5,"
                + "\"status\":\"approved\"},{\"id\":8,\"amount\":1,\"status\":\"in_process\"}]")));
        assertThat(api.refunds("55")).extracting(GatewayRefund::status).containsExactly("approved", "in_process");
        MP.stubFor(post(urlEqualTo("/v1/payments/56/refunds")).willReturn(json("{\"message\":\"insufficient_amount\"}")
                .withStatus(400)));
        assertThatThrownBy(() -> api.refund("56", null, "k3")).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.refundFailReason()).isEqualTo("saldo"));
    }

    /**
     * Convidado de link.
     */
    @Test
    @DisplayName("linkGuest: token ruim ou de link não pago é vazio")
    void linkGuest() {
        assertThat(api.linkGuest("curto")).isEmpty();
        assertThat(api.linkGuest("A".repeat(43))).isEmpty();
    }

    /**
     * Desativar Pix pendentes.
     */
    @Test
    @DisplayName("cancelPendingCharges: só os pendentes são desativados; falha não é erro")
    void cancelPending() {
        payment(61, "pending", "1", 10);
        payment(62, "approved", "1", 10);
        MP.stubFor(put(urlEqualTo("/v1/payments/61")).willReturn(json("{}")));
        api.cancelPendingCharges(List.of("61", "62", "63"));
        MP.verify(1, putRequestedFor(urlPathEqualTo("/v1/payments/61")));
        MP.verify(0, putRequestedFor(urlPathEqualTo("/v1/payments/62")));
    }

    /**
     * Adaptador.
     */
    @Test
    @DisplayName("adaptador: Pix sem copia e cola é erro; resposta vazia; id com caracteres especiais codificado")
    void adapter() {
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":1,\"status\":\"pending\"}")));
        assertThatThrownBy(() -> gateway.createPix(new PaymentGateway.PixCharge("1", "k", Money.ofCents(100), "d",
                "e@x.com", "Ana", Instant.now()))).isInstanceOf(GatewayException.class);
        MP.stubFor(put(urlEqualTo("/v1/payments/a%20b")).willReturn(json("")));
        gateway.cancel("a b");
        MP.verify(putRequestedFor(urlEqualTo("/v1/payments/a%20b")));
        assertThat(settings.webhookMaxSkew()).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * Depois do commit, sem transação.
     */
    @Test
    @DisplayName("AfterCommit sem transação roda na hora")
    void afterCommitWithoutTransaction() {
        AtomicBoolean ran = new AtomicBoolean();
        AfterCommit.run(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    /**
     * Tarefas agendadas.
     */
    @Test
    @DisplayName("tarefas: limpeza das entregas; conciliação dos links; tentativa sem resposta de link e sem cobrança")
    void jobs() {

        // ── 1. Entregas antigas somem.
        jdbc.sql("INSERT INTO webhook_deliveries (request_id, data_id, received_at) VALUES (:id, '1', now() - "
                + "interval '40 days')").param("id", "velha-" + UUID.randomUUID()).update();
        purgeDeliveries.run();
        assertThat(jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE received_at < now() - interval '30 days'")
                .query(Long.class).single()).isZero();
        assertThat(purgeDeliveries.name()).isEqualTo("payments.purge-webhook-deliveries");
        reconcileLinks.run();

        // ── 2. Tentativa de link sem resposta, com cobrança aprovada; e de pedido sem cobrança nenhuma.
        long order = jdbc.sql("""
                INSERT INTO orders (user_id, number, customer, product, qty, unit_price)
                VALUES ((SELECT id FROM users WHERE username = 'dona'),
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM orders), 'Ana', 'Bolo', 1, 30) RETURNING id""")
                .query(Long.class).single();
        long link = jdbc.sql("INSERT INTO order_pay_links (order_id, token, amount) VALUES (:o, :t, 30) RETURNING id")
                .param("o", order).param("t", UUID.randomUUID().toString()).query(Long.class).single();
        UUID attempt = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payment_attempts (id, pay_link_id, client_ip, method, state, created_at)
                VALUES (:id, :link, '1.2.3.4', 'cartao', 'in_flight', now() - interval '3 minutes')""")
                .param("id", attempt).param("link", link).update();
        long approved = paymentId();
        MP.stubFor(get(urlPathEqualTo("/v1/payments/search")).willReturn(json("{\"results\":[{\"id\":" + approved
                + ",\"status\":\"approved\",\"external_reference\":\"link:" + link + "\",\"transaction_amount\":30}]}")));
        expireStaleAttempts.run();
        assertThat(value("SELECT paid_mp_id FROM order_pay_links WHERE id = :id", link))
                .isEqualTo(String.valueOf(approved));
        MP.stubFor(get(urlPathEqualTo("/v1/payments/search")).willReturn(json("{\"results\":[]}")));
        UUID lost = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payment_attempts (id, pay_link_id, client_ip, method, state, created_at)
                VALUES (:id, :link, '1.2.3.4', 'cartao', 'in_flight', now() - interval '3 minutes')""")
                .param("id", lost).param("link", link).update();
        expireStaleAttempts.run();
        assertThat(jdbc.sql("SELECT state FROM payment_attempts WHERE id = :id").param("id", lost).query(String.class)
                .single()).isEqualTo("error");
    }
}
