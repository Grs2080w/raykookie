package br.com.kntap.raykookie.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras puras de pagamentos: assinatura do webhook (vetor do Node), motivos de recusa, datas do Mercado Pago,
 * motivo de falha da devolução e configuração.
 */
class PaymentRulesTest {

    /** Janela de 10 minutos (G18). */
    private static final Duration SKEW = Duration.ofMinutes(10);

    /** Vetor do Node: {@code validSignature('segredo-webhook', '123456', 'ts=1700000000,v1=…', 'req-1')}. */
    private static final String VECTOR = "ts=1700000000,v1=5d8a4111a1d2d2e831b3436b3448176d291c922a704d059220b048e0b21b9231";

    /**
     * Assinatura.
     */
    @Test
    @DisplayName("assinatura: vetor do Node aceito; 11 min depois recusado; partes faltando e v1 errado recusados")
    void signature() {

        // ── 1. Vetor de ouro e a janela (G18).
        Instant signed = Instant.ofEpochSecond(1_700_000_000L);
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", VECTOR, "req-1", signed, SKEW)).isTrue();
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", VECTOR, "req-1",
                signed.plus(Duration.ofMinutes(11)), SKEW)).isFalse();
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", VECTOR, "req-1",
                signed.minus(Duration.ofMinutes(9)), SKEW)).isTrue();

        // ── 2. Recusas.
        assertThat(WebhookSignature.valid("", "123456", VECTOR, "req-1", signed, SKEW)).isFalse();
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", null, "req-1", signed, SKEW)).isFalse();
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", "ts=1700000000", "req-1", signed, SKEW))
                .isFalse();
        assertThat(WebhookSignature.valid("segredo-webhook", "123456", "ts=abc,v1=00", "req-1", signed, SKEW))
                .isFalse();
        assertThat(WebhookSignature.valid("segredo-webhook", "123457", VECTOR, "req-1", signed, SKEW)).isFalse();
        assertThat(WebhookSignature.valid("outro", "123456", VECTOR, "req-1", signed, SKEW)).isFalse();
    }

    /**
     * Variações do manifesto.
     */
    @Test
    @DisplayName("assinatura: id alfanumérico em minúsculas, sem request-id, ts em milissegundos")
    void signatureVariants() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String upper = "ts=1700000000,v1=" + WebhookSignature.hmac("s", "id:abc9;request-id:r;ts:1700000000;");
        assertThat(WebhookSignature.valid("s", "ABC9", upper, "r", now, SKEW)).isTrue();
        String noRequest = "ts=1700000000,v1=" + WebhookSignature.hmac("s", "id:1;ts:1700000000;");
        assertThat(WebhookSignature.valid("s", "1", noRequest, null, now, SKEW)).isTrue();
        assertThat(WebhookSignature.valid("s", "1", noRequest, "", now, SKEW)).isTrue();
        String millis = " ts = 1700000000000 , v1=" + WebhookSignature.hmac("s", "id:1;ts:1700000000000;");
        assertThat(WebhookSignature.valid("s", "1", millis, null, now, SKEW)).isTrue();
    }

    /**
     * Motivos de recusa e datas.
     */
    @Test
    @DisplayName("recusa do cartão em português (genérica se desconhecida) e data em -03:00")
    void reasonsAndDates() {
        assertThat(CardFailReasons.of("cc_rejected_insufficient_amount")).isEqualTo("Saldo ou limite insuficiente.");
        assertThat(CardFailReasons.of("cc_rejected_other_reason")).isEqualTo(CardFailReasons.GENERIC);
        assertThat(CardFailReasons.of(null)).isEqualTo(CardFailReasons.GENERIC);
        assertThat(MpDates.format(Instant.parse("2026-10-08T15:00:00.123456Z")))
                .isEqualTo("2026-10-08T12:00:00.123-03:00");
    }

    /**
     * Pagamento do gateway e desfechos.
     */
    @Test
    @DisplayName("pagamento: aprovado, aberto, devolvido; desfecho pago")
    void paymentFlags() {
        GatewayPayment refunded = new GatewayPayment("1", "approved", "", "1", Money.ofCents(100), Money.ofCents(10),
                null, null);
        assertThat(refunded.approved()).isTrue();
        assertThat(refunded.refundedOrChargedBack()).isTrue();
        assertThat(new GatewayPayment("1", "in_process", "", "", Money.ZERO, Money.ZERO, null, null).open()).isTrue();
        assertThat(new GatewayPayment("1", "charged_back", "", "", Money.ZERO, Money.ZERO, null, null)
                .refundedOrChargedBack()).isTrue();
        assertThat(ConfirmationOutcome.REJECTED.paid()).isFalse();
        assertThat(ConfirmationOutcome.DUPLICATE_REFUNDED.paid()).isTrue();
    }

    /**
     * Motivo de falha da devolução.
     */
    @Test
    @DisplayName("falha da devolução: saldo, prazo ou erro")
    void refundFailReason() {
        assertThat(new GatewayException(428, "", null).refundFailReason()).isEqualTo("saldo");
        assertThat(new GatewayException(400, "insufficient_amount", null).refundFailReason()).isEqualTo("saldo");
        assertThat(new GatewayException(400, "refund_period exceeded", null).refundFailReason()).isEqualTo("prazo");
        assertThat(new GatewayException(409, "the period ended", null).refundFailReason()).isEqualTo("prazo");
        assertThat(new GatewayException(500, "x".repeat(400), null).refundFailReason()).isEqualTo("erro");
        assertThat(new GatewayException(0, "timeout", null).status()).isZero();
    }

    /**
     * Configuração.
     */
    @Test
    @DisplayName("configuração: validade do Pix entre 31 min e 30 dias; e-mail do pagador pelo domínio do site")
    void settings() {
        PaymentSettings defaults = new PaymentSettings("", "", SKEW, 10, "https://www.loja.com.br/", "");
        assertThat(defaults.configured()).isFalse();
        assertThat(defaults.pixValidity()).isEqualTo(Duration.ofMinutes(31));
        assertThat(defaults.payerEmail()).isEqualTo("pagamentos@loja.com.br");
        assertThat(defaults.siteUrl("/x")).isEqualTo("https://www.loja.com.br/x");
        PaymentSettings custom = new PaymentSettings("TEST", "s", SKEW, 99_999, "https://loja.com.br", "p@x.com");
        assertThat(custom.pixValidity()).isEqualTo(Duration.ofDays(30));
        assertThat(custom.payerEmail()).isEqualTo("p@x.com");
        assertThat(custom.webhookSecret()).isEqualTo("s");
    }
}
