package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.ShopOrderLocking;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.ConfirmationOutcome;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.persistence.AfterCommit;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirma o pagamento de um pedido da loja. É o ÚNICO lugar do sistema que marca um pedido como pago — webhook,
 * cartão, cancelamento e conciliação passam todos por aqui (G02, G03, G09).
 */
@Service
public class ConfirmPaymentUseCase {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(ConfirmPaymentUseCase.class);

    /** Pedido com trava de linha. */
    private final ShopOrderLocking orders;

    /** Histórico do pedido. */
    private final OrdersApi ordersApi;

    /** Devoluções (módulo de devoluções). */
    private final ObjectProvider<RefundRequester> refunds;

    /** Gateway (devolução de pagamento em dobro). */
    private final PaymentGateway gateway;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Alertas de operação. */
    private final OpsAlert alert;

    /**
     * Cria o caso de uso com as portas de que ele precisa.
     *
     * @param orders    acesso ao pedido com trava de linha
     * @param ordersApi histórico do pedido
     * @param refunds   pede devolução quando o pagamento não pode ficar
     * @param gateway   gateway de pagamento
     * @param events    publica os avisos de pagamento
     * @param alert     alertas de operação
     */
    public ConfirmPaymentUseCase(ShopOrderLocking orders, OrdersApi ordersApi, ObjectProvider<RefundRequester> refunds,
            PaymentGateway gateway, ApplicationEventPublisher events, OpsAlert alert) {
        this.orders = orders;
        this.ordersApi = ordersApi;
        this.refunds = refunds;
        this.gateway = gateway;
        this.events = events;
        this.alert = alert;
    }

    /**
     * Aplica um pagamento aprovado ao pedido.
     *
     * @param orderId      id do pedido da loja ({@code shop_orders.id})
     * @param payment      pagamento consultado direto no gateway (nunca o corpo da notificação)
     * @param installments parcelas do cartão (nulo = Pix)
     * @param via          {@code pix}, {@code cartao} ou {@code conciliacao} (vai para o histórico)
     * @return o desfecho: pago agora, já estava pago, devolvido ou recusado
     */
    @Transactional
    public ConfirmationOutcome confirm(long orderId, GatewayPayment payment, Integer installments, String via) {

        // ── 1. Trava o pedido: avisos simultâneos do mesmo pagamento ficam em fila aqui,
        //       e só o primeiro enxerga o pedido ainda sem pagamento.
        ShopOrderLocking.PayableOrder order = orders.lockById(orderId)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));

        // ── 2. Recusa pagamento que não é deste pedido ou que não cobre o total.
        if (!payment.approved() || !order.ownsPayment(payment.id()) || payment.amount().isLessThan(order.total())) {
            LOG.warn("Pagamento {} não aplicado ao pedido {} (de outro pedido ou valor menor)", payment.id(), orderId);
            return ConfirmationOutcome.REJECTED;
        }

        // ── 3. Pagamento repetido (já pago com OUTRO id): a segunda cobrança volta inteira.
        if (order.paid() && !order.paidWith(payment.id())) {
            refundDuplicate(orderId, payment);
            return ConfirmationOutcome.DUPLICATE_REFUNDED;
        }

        // ── 4. Reenvio do mesmo pagamento: nada muda (idempotente).
        if (order.paid()) {
            return ConfirmationOutcome.ALREADY_PAID;
        }

        // ── 5. Marca como pago e registra o evento na mesma transação.
        orders.markPaid(orderId, payment.id(), installments);
        ordersApi.logEvent(orderId, "mercadopago", "pago", paidEvent(payment, installments, via));

        // ── 6. Pedido já cancelado: aceita o dinheiro só para devolvê-lo inteiro.
        if (order.cancelled()) {
            refundLate(orderId);
            return ConfirmationOutcome.PAID_AFTER_CANCEL_REFUNDED;
        }
        notifyPaid(order);
        return ConfirmationOutcome.PAID_NOW;
    }

    /**
     * Avisa o cliente que o cartão em análise foi recusado, se o pedido ainda espera pagamento.
     *
     * @param orderId id do pedido da loja
     * @param reason  motivo da recusa, em português
     */
    @Transactional
    public void notifyCardRefused(long orderId, String reason) {
        orders.lockById(orderId).filter(order -> !order.paid() && !order.cancelled())
                .ifPresent(order -> events.publishEvent(new CustomerNotice.CardRefused(new CustomerNotice.OrderRef(
                        order.id(), order.code(), order.customer(), order.total(), order.fulfillment(),
                        order.timeWindow(), false), reason)));
    }

    /**
     * Dados do evento "pago" no histórico.
     *
     * @param payment      pagamento
     * @param installments parcelas
     * @param via          meio
     * @return os dados
     */
    private static Map<String, Object> paidEvent(GatewayPayment payment, Integer installments, String via) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment", payment.id());
        data.put("amount", NodeRows.number(payment.amount().toDecimal()));
        data.put("via", via);
        if (installments != null) {
            data.put("installments", installments);
        }
        return data;
    }

    /**
     * Avisa a dona e o cliente (depois do commit).
     *
     * @param order pedido
     */
    private void notifyPaid(ShopOrderLocking.PayableOrder order) {
        events.publishEvent(new OwnerNotice.Paid(order.owner(), order.id(), order.code(), order.contactName(),
                order.total(), order.items()));
        events.publishEvent(new CustomerNotice.Paid(new CustomerNotice.OrderRef(order.id(), order.code(),
                order.customer(), order.total(), order.fulfillment(), order.timeWindow(), true)));
    }

    /**
     * Pedido cancelado e pago: devolução de tudo pelo módulo de devoluções (registrada nesta transação).
     *
     * @param orderId pedido
     */
    private void refundLate(long orderId) {
        RefundRequester requester = refunds.getIfAvailable();
        if (requester == null) {
            alert.paymentProblem("pagamento de pedido cancelado sem o módulo de devoluções", Map.of("order", orderId));
            return;
        }
        requester.refundLatePayment(orderId);
    }

    /**
     * Segunda cobrança aprovada de um pedido já pago: devolve inteira depois do commit e avisa a operação.
     *
     * @param orderId pedido
     * @param payment pagamento repetido
     */
    private void refundDuplicate(long orderId, GatewayPayment payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment", payment.id());
        data.put("amount", NodeRows.number(payment.amount().toDecimal()));
        ordersApi.logEvent(orderId, "sistema", "pagamento_duplicado", data);
        AfterCommit.run(() -> {
            Map<String, Object> context = Map.of("order", orderId, "payment", payment.id());
            alert.paymentProblem("pagamento em dobro: a segunda cobrança foi devolvida", context);
            try {
                gateway.refund(payment.id(), null, "rayka-duplicate-" + payment.id());
            } catch (RuntimeException e) {
                alert.refundFailed("devolução de pagamento em dobro", context, e);
            }
        });
    }
}
