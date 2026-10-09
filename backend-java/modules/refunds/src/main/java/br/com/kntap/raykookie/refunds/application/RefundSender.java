package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import br.com.kntap.raykookie.refunds.domain.RetrySchedule;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manda a devolução ao Mercado Pago ({@code sendRefund}): a linha fica travada durante o envio (um envio
 * simultâneo da mesma devolução não acontece) e a chave de idempotência é fixa por devolução, então repetir nunca
 * devolve duas vezes. Nunca lança erro.
 */
@Service
public class RefundSender {

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

    /** Alertas. */
    private final OpsAlert alert;

    /** Transação própria (o envio roda também depois do commit de outra). */
    private final TransactionTemplate transactions;

    /**
     * Cria o serviço.
     *
     * @param orders       pedidos
     * @param refunds      devoluções
     * @param payments     gateway
     * @param history      histórico
     * @param events       avisos
     * @param alert        alertas
     * @param transactions gerenciador de transações
     */
    public RefundSender(RefundOrders orders, Refunds refunds, PaymentsApi payments, OrdersApi history,
            ApplicationEventPublisher events, OpsAlert alert, PlatformTransactionManager transactions) {

        // ── 1. Dados e gateway.
        this.orders = orders;
        this.refunds = refunds;
        this.payments = payments;
        this.history = history;

        // ── 2. Avisos e transação nova a cada envio.
        this.events = events;
        this.alert = alert;
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Manda (ou refaz) a devolução.
     *
     * @param refundId devolução
     * @return {@code concluido}, {@code processando}, {@code falhou} ou {@code ignorado}
     */
    public String send(long refundId) {
        return transactions.execute(status -> {

            // ── 1. Reserva; sem o pagamento do pedido, falha.
            Optional<Refunds.Claimed> found = refunds.claim(refundId);
            if (found.isEmpty()) {
                return "ignorado";
            }
            Refunds.Claimed claimed = found.get();
            RefundOrders.OrderState o = orders.load(claimed.orderId(), false).orElseThrow();
            if (o.paidMpId() == null) {
                return failed(claimed, o, "erro", "Pedido sem identificação do pagamento", null);
            }

            // ── 2. Tudo o que resta vai sem valor (o Mercado Pago devolve o total); parcial, com o valor exato.
            boolean wholeRest = !claimed.amount().isLessThan(o.total().minus(o.refundedTotal()));
            Money amount = wholeRest && o.refundedTotal().isZero() ? null : claimed.amount();
            GatewayRefund mr;
            try {
                mr = payments.refund(o.paidMpId(), amount, "rayka-refund-" + refundId);
            } catch (GatewayException e) {
                return failed(claimed, o, e.refundFailReason(), e.getMessage(), e);
            }

            // ── 3. Grava a resposta e avisa.
            return recordSent(claimed, o, mr);
        });
    }

    /**
     * Grava a resposta do Mercado Pago.
     *
     * @param c  devolução
     * @param o  pedido
     * @param mr resposta
     * @return a nova situação
     */
    private String recordSent(Refunds.Claimed c, RefundOrders.OrderState o, GatewayRefund mr) {

        // ── 1. Concluída: soma no pedido; senão, fica processando.
        boolean done = mr.approved();
        boolean first = refunds.finishSent(c.id(), done, mr.id(), mr.status());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", c.id());
        data.put("amount", NodeRows.number(c.amount().toDecimal()));
        if (done && first) {
            orders.addRefunded(c.orderId(), c.amount());
            data.put("mp_refund_id", mr.id());
            history.logEvent(c.orderId(), "mercadopago", "devolucao_concluida", data);
        } else if (first) {
            data.put("mp_status", mr.status());
            history.logEvent(c.orderId(), "mercadopago", "devolucao_enviada", data);
        }

        // ── 2. Avisos: o cliente sempre; a dona só se antes tinha falhado.
        if (done && first) {
            events.publishEvent(new CustomerNotice.Refund(o.ref(), "concluida", c.amount(), null, null));
            if (c.attempts() > 1) {
                events.publishEvent(new OwnerNotice.Refund(o.owner(), "concluida", c.id(), o.code(), null,
                        c.amount(), false, null, 0, false));
            }
        }
        return done ? "concluido" : "processando";
    }

    /**
     * Falhou: agenda a próxima tentativa, registra e avisa a dona e a operação.
     *
     * @param c      devolução
     * @param o      pedido
     * @param why    {@code saldo}, {@code prazo} ou {@code erro}
     * @param detail detalhe
     * @param error  erro (pode ser nulo)
     * @return {@code falhou}
     */
    private String failed(Refunds.Claimed c, RefundOrders.OrderState o, String why, String detail, Throwable error) {

        // ── 1. Próxima tentativa e histórico.
        Optional<Duration> wait = RetrySchedule.after(c.attempts());
        refunds.markFailed(c.id(), why, wait.map(d -> (int) d.toMinutes()).orElse(null));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", c.id());
        data.put("why", why);
        data.put("detail", detail.length() > 300 ? detail.substring(0, 300) : detail);
        data.put("attempt", c.attempts());
        history.logEvent(c.orderId(), "sistema", "devolucao_falhou", data);

        // ── 2. Avisa a dona e a operação.
        events.publishEvent(new OwnerNotice.Refund(o.owner(), "saldo".equals(why) ? "saldo" : "falha", c.id(),
                o.code(), null, c.amount(), false, null, 0, wait.isEmpty()));
        alert.refundFailed("devolução " + c.id() + " falhou (" + why + ")", Map.of("refund", c.id(), "order",
                c.orderId()), error);
        return "falhou";
    }

    /**
     * Refaz as devoluções cuja hora chegou (job).
     *
     * @return quantas foram tentadas
     */
    public int retryDue() {
        List<Long> due = refunds.due(RetrySchedule.MAX_ATTEMPTS);
        due.forEach(this::send);
        return due.size();
    }
}
