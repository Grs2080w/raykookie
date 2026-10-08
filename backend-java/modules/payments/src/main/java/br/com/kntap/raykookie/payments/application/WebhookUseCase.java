package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.application.ports.WebhookDeliveries;
import br.com.kntap.raykookie.payments.domain.ConfirmationOutcome;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.payments.domain.WebhookSignature;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Notificações do Mercado Pago ({@code app/api/webhooks/mercadopago/route.ts}). O pedido só vira "pago" quando o
 * pagamento consultado direto na API está aprovado; assinatura fora da janela (G18) é recusada e reenvio do mesmo
 * {@code x-request-id} é ignorado.
 */
@Service
public class WebhookUseCase {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(WebhookUseCase.class);

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Confirmação única. */
    private final ConfirmPaymentUseCase confirm;

    /** Pagamento de link. */
    private final LinkPayments links;

    /** Entregas já processadas. */
    private final WebhookDeliveries deliveries;

    /** Devoluções. */
    private final ObjectProvider<RefundRequester> refunds;

    /** Histórico. */
    private final OrdersApi orders;

    /** Configuração. */
    private final PaymentSettings settings;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria o caso de uso.
     *
     * @param gateway    gateway
     * @param confirm    confirmação única
     * @param links      pagamento de link
     * @param deliveries entregas já processadas
     * @param refunds    devoluções
     * @param orders     histórico
     * @param settings   configuração
     * @param clock      relógio
     */
    public WebhookUseCase(PaymentGateway gateway, ConfirmPaymentUseCase confirm, LinkPayments links,
            WebhookDeliveries deliveries, ObjectProvider<RefundRequester> refunds, OrdersApi orders,
            PaymentSettings settings, Clock clock) {

        // ── 1. Gateway e aplicação do pagamento.
        this.gateway = gateway;
        this.confirm = confirm;
        this.links = links;
        this.deliveries = deliveries;

        // ── 2. Devoluções, histórico e configuração.
        this.refunds = refunds;
        this.orders = orders;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Processa uma notificação.
     *
     * @param n a notificação
     * @return status HTTP e corpo
     */
    public Reply handle(Notification n) {

        // ── 1. Configurado, assinado e dentro da janela.
        if (settings.webhookSecret().isEmpty() || !settings.configured()) {
            return new Reply(503, Map.of("error", "Webhook não configurado"));
        }
        if (!WebhookSignature.valid(settings.webhookSecret(), n.dataId(), n.signature(), n.requestId(),
                clock.instant(), settings.webhookMaxSkew())) {
            return new Reply(401, Map.of("error", "Assinatura inválida"));
        }
        if (!"payment".equals(n.type()) || n.dataId().isEmpty()) {
            return new Reply(200, Map.of("ok", true, "ignored", true));
        }

        // ── 2. Entrega repetida (G18).
        boolean tracked = n.requestId() != null && !n.requestId().isEmpty();
        if (tracked && deliveries.seen(n.requestId())) {
            return new Reply(200, Map.of("ok", true, "duplicate", true));
        }

        // ── 3. Processa; falha devolve 500 para o Mercado Pago tentar de novo.
        try {
            Map<String, Object> body = process(n.dataId());
            if (tracked) {
                deliveries.remember(n.requestId(), n.dataId());
            }
            return new Reply(200, body);
        } catch (RuntimeException e) {
            LOG.error("Falha ao processar a notificação do pagamento {}", n.dataId(), e);
            return new Reply(500, Map.of("error", "Falha ao processar"));
        }
    }

    /**
     * Consulta o pagamento e aplica.
     *
     * @param dataId pagamento
     * @return o corpo da resposta
     */
    private Map<String, Object> process(String dataId) {

        // ── 1. Nunca confia no corpo: consulta o pagamento.
        GatewayPayment pay = gateway.getPayment(dataId);
        String ref = pay.externalReference() == null ? "" : pay.externalReference();
        if (ref.startsWith(LinkPayments.LINK_REF)) {
            Long linkId = number(ref.substring(LinkPayments.LINK_REF.length()));
            return linkId == null ? ok(pay.status()) : links.handle(pay, linkId);
        }
        Long orderId = number(ref);
        if (orderId == null) {
            return ok(pay.status());
        }

        // ── 2. Devolução ou estorno: concilia.
        if (pay.refundedOrChargedBack()) {
            RefundRequester requester = refunds.getIfAvailable();
            if (requester != null) {
                requester.reconcileRefunds(orderId, pay.id());
            }
            if ("charged_back".equals(pay.status())) {
                orders.logEvent(orderId, "mercadopago", "estorno_cartao", Map.of("payment", pay.id()));
            }
            return ok(pay.status());
        }

        // ── 3. Aprovado: a confirmação única decide.
        if (!pay.approved()) {
            return ok(pay.status());
        }
        ConfirmationOutcome outcome = confirm.confirm(orderId, pay, null, "pix");
        return outcome == ConfirmationOutcome.PAID_AFTER_CANCEL_REFUNDED ? Map.of("ok", true, "refunded", true)
                : Map.of("ok", true);
    }

    /**
     * {@code {"ok":true,"status":…}}.
     *
     * @param status status do pagamento
     * @return o corpo
     */
    private static Map<String, Object> ok(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("status", status);
        return body;
    }

    /**
     * Número inteiro de uma referência ({@code Number.isInteger(Number(ref))}).
     *
     * @param text referência
     * @return o número, ou nulo
     */
    static Long number(String text) {
        return text.matches("\\d{1,18}") ? Long.valueOf(text) : null;
    }

    /**
     * Notificação recebida.
     *
     * @param type      {@code type} (corpo ou query)
     * @param dataId    {@code data.id}
     * @param signature {@code x-signature}
     * @param requestId {@code x-request-id}
     */
    public record Notification(String type, String dataId, String signature, String requestId) {
    }

    /**
     * Resposta.
     *
     * @param status status HTTP
     * @param body   corpo
     */
    public record Reply(int status, Map<String, Object> body) {
    }
}
