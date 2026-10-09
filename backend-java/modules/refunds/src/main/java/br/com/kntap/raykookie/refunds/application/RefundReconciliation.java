package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conciliação com o Mercado Pago ({@code reconcileRefunds}): fecha as nossas devoluções concluídas lá e registra as
 * feitas direto no painel do Mercado Pago (estorno de cartão incluído). Repetir não soma de novo.
 */
@Service
public class RefundReconciliation {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(RefundReconciliation.class);

    /** Pedidos. */
    private final RefundOrders orders;

    /** Devoluções. */
    private final Refunds refunds;

    /** Gateway. */
    private final PaymentsApi payments;

    /** Histórico. */
    private final OrdersApi history;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transação nova por devolução. */
    private final TransactionTemplate transactions;

    /**
     * Cria o serviço.
     *
     * @param orders       pedidos
     * @param refunds      devoluções
     * @param payments     gateway
     * @param history      histórico
     * @param events       avisos
     * @param transactions gerenciador de transações
     */
    public RefundReconciliation(RefundOrders orders, Refunds refunds, PaymentsApi payments, OrdersApi history,
            ApplicationEventPublisher events, PlatformTransactionManager transactions) {

        // ── 1. Dados e gateway.
        this.orders = orders;
        this.refunds = refunds;
        this.payments = payments;
        this.history = history;

        // ── 2. Avisos e transação própria.
        this.events = events;
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Confere as devoluções de um pagamento.
     *
     * @param orderId   pedido
     * @param paymentId pagamento
     */
    public void reconcile(long orderId, String paymentId) {
        List<GatewayRefund> list;
        try {
            list = payments.refunds(paymentId);
        } catch (GatewayException e) {
            LOG.warn("Mercado Pago sem resposta ao conciliar as devoluções do pagamento {}", paymentId, e);
            return;
        }
        for (GatewayRefund mr : list) {
            if (mr.approved()) {
                transactions.execute(status -> apply(orderId, mr)).ifPresent(o -> events.publishEvent(
                        new CustomerNotice.Refund(o.ref(), "concluida", mr.amount(), null, null)));
            }
        }
    }

    /**
     * Aplica uma devolução concluída no Mercado Pago.
     *
     * @param orderId pedido
     * @param mr      devolução
     * @return o pedido, se algo mudou
     */
    private Optional<RefundOrders.OrderState> apply(long orderId, GatewayRefund mr) {

        // ── 1. Nossa (fecha) ou do painel (registra); já concluída não muda nada.
        Optional<Refunds.Known> known = refunds.byMpRefundId(mr.id());
        Optional<RefundOrders.OrderState> order = orders.load(orderId, true);
        if (order.isEmpty() || known.filter(k -> "concluido".equals(k.status())).isPresent()) {
            return Optional.empty();
        }
        RefundOrders.OrderState o = order.get();
        if (known.isPresent()) {
            refunds.complete(known.get().id());
        } else {
            refunds.insertExternal(orderId, o.owner(), o.customer(), mr.amount(), mr.id());
        }

        // ── 2. Soma no pedido e registra.
        orders.addRefunded(orderId, mr.amount());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", NodeRows.number(mr.amount().toDecimal()));
        data.put("mp_refund_id", mr.id());
        data.put("reconciled", true);
        history.logEvent(orderId, "mercadopago", "devolucao_concluida", data);
        return order;
    }

    /**
     * Devoluções ainda "processando" com resposta do Mercado Pago (job a cada 6 h).
     *
     * @return quantos pedidos foram conferidos
     */
    public int reconcileProcessing() {
        List<Refunds.Processing> processing = refunds.processing();
        processing.forEach(p -> reconcile(p.orderId(), p.paidMpId()));
        return processing.size();
    }
}
