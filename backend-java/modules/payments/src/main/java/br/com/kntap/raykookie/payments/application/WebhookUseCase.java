package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.application.ports.WebhookDeliveries;
import br.com.kntap.raykookie.payments.domain.ConfirmationOutcome;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.payments.domain.WebhookSignature;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Notificações do Mercado Pago ({@code app/api/webhooks/mercadopago/route.ts}). O pedido só vira "pago" quando o
 * pagamento consultado direto na API está aprovado; assinatura fora da janela (G18) é recusada e reenvio do mesmo
 * {@code x-request-id} é ignorado. Além de {@code payment}, trata o alerta de fraude ({@code stop_delivery_op_wh}) e
 * confirma o recebimento dos pedidos do Mercado Pago ({@code order}); qualquer outro tipo só é confirmado (200) para o
 * Mercado Pago não reenviar.
 */
@Service
public class WebhookUseCase {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(WebhookUseCase.class);

    /** Tipo da notificação de pagamento. */
    private static final String PAYMENT = "payment";

    /** Tipo do alerta de fraude depois do pedido processado. */
    private static final String FRAUD = "stop_delivery_op_wh";

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

    /** Pedidos da loja e seus pagamentos. */
    private final OrderCharges charges;

    /** Alertas de operação. */
    private final OpsAlert alert;

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
     * @param charges    pedidos da loja e seus pagamentos
     * @param alert      alertas de operação
     * @param settings   configuração
     * @param clock      relógio
     */
    public WebhookUseCase(PaymentGateway gateway, ConfirmPaymentUseCase confirm, LinkPayments links,
            WebhookDeliveries deliveries, ObjectProvider<RefundRequester> refunds, OrdersApi orders,
            OrderCharges charges, OpsAlert alert, PaymentSettings settings, Clock clock) {

        // ── 1. Gateway e aplicação do pagamento.
        this.gateway = gateway;
        this.confirm = confirm;
        this.links = links;
        this.deliveries = deliveries;

        // ── 2. Devoluções, histórico, pedidos e alertas.
        this.refunds = refunds;
        this.orders = orders;
        this.charges = charges;
        this.alert = alert;

        // ── 3. Configuração e relógio.
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
        if (!signed(n)) {
            LOG.warn("Notificação do Mercado Pago recusada: assinatura inválida (tipo {}, request-id {}). "
                    + "Confira se MP_WEBHOOK_SECRET é o segredo de produção do aplicativo.", n.type(), n.requestId());
            return new Reply(401, Map.of("error", "Assinatura inválida"));
        }

        // ── 2. Tipo que o sistema não trata: só confirma o recebimento, para o Mercado Pago não reenviar.
        boolean fraud = FRAUD.equals(n.type());
        if (!PAYMENT.equals(n.type()) && !fraud) {
            LOG.info("Notificação do Mercado Pago do tipo '{}' recebida, sem tratamento próprio", n.type());
            return new Reply(200, Map.of("ok", true, "ignored", true));
        }
        String subject = fraud ? n.paymentId() : n.dataId();
        return subject.isEmpty() ? new Reply(200, Map.of("ok", true, "ignored", true)) : deliver(n, fraud, subject);
    }

    /**
     * Processa uma notificação de pagamento ou de fraude já assinada, sem repetir entregas.
     *
     * @param n       a notificação
     * @param fraud   {@code true} para alerta de fraude
     * @param subject pagamento a que ela se refere
     * @return status HTTP e corpo
     */
    private Reply deliver(Notification n, boolean fraud, String subject) {

        // ── 1. Entrega repetida (G18).
        boolean tracked = n.requestId() != null && !n.requestId().isEmpty();
        if (tracked && deliveries.seen(n.requestId())) {
            return new Reply(200, Map.of("ok", true, "duplicate", true));
        }

        // ── 2. Processa; falha devolve 500 para o Mercado Pago tentar de novo (o alerta de fraude não é reenviado).
        try {
            Map<String, Object> body = fraud ? fraudAlert(n) : process(n.dataId());
            if (tracked) {
                deliveries.remember(n.requestId(), subject);
            }
            return new Reply(200, body);
        } catch (RuntimeException e) {
            LOG.error("Falha ao processar a notificação {} do pagamento {}", n.type(), subject, e);
            return new Reply(500, Map.of("error", "Falha ao processar"));
        }
    }

    /**
     * Confere a assinatura. O alerta de fraude traz o pagamento em {@code data.payment_id} e pode ser assinado com
     * ele (ou com o pedido do comerciante) no lugar de {@code data.id}; aceita qualquer um desses, desde que o HMAC
     * com o segredo bata.
     *
     * @param n a notificação
     * @return {@code true} se a assinatura é válida e recente
     */
    private boolean signed(Notification n) {
        List<String> ids = FRAUD.equals(n.type())
                ? List.of(n.dataId(), n.paymentId(), n.data().getOrDefault("merchant_order", ""))
                : List.of(n.dataId());
        return ids.stream().anyMatch(id -> WebhookSignature.valid(settings.webhookSecret(), id, n.signature(),
                n.requestId(), clock.instant(), settings.webhookMaxSkew()));
    }

    /**
     * Alerta de fraude: acha o pedido pelo pagamento, guarda o aviso no histórico e avisa a dona para não entregar.
     *
     * @param n a notificação
     * @return o corpo da resposta
     */
    private Map<String, Object> fraudAlert(Notification n) {

        // ── 1. Pedido do pagamento.
        String paymentId = n.paymentId();
        String merchantOrder = n.data().getOrDefault("merchant_order", "");
        Long orderId = charges.orderIdByPayment(paymentId).orElse(null);

        // ── 2. Registra e avisa; sem pedido conhecido, só alerta a operação.
        if (orderId != null && confirm.registerFraudAlert(orderId, paymentId, merchantOrder)) {
            return Map.of("ok", true, "order", orderId);
        }
        alert.paymentProblem("alerta de fraude do Mercado Pago sobre pagamento sem pedido conhecido",
                Map.of("payment", paymentId, "merchant_order", merchantOrder));
        return Map.of("ok", true, "unknown", true);
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
     * @param data      campos simples de {@code data} do corpo (ex.: {@code payment_id}, {@code merchant_order})
     */
    public record Notification(String type, String dataId, String signature, String requestId,
            Map<String, String> data) {

        /**
         * Pagamento a que a notificação se refere: {@code data.payment_id} (alerta de fraude) ou {@code data.id}.
         *
         * @return o id do pagamento (vazio se não houver)
         */
        public String paymentId() {
            String id = data.getOrDefault("payment_id", "");
            return id.isEmpty() ? dataId : id;
        }
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
