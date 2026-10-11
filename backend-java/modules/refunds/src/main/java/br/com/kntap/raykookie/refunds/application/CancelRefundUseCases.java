package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.ports.PaidOrderCanceller;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import br.com.kntap.raykookie.persistence.AfterCommit;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancelar devolvendo ({@code cancelCore}, {@code cancelAndRefund}, {@code ownerCancel}) e o pagamento que chegou
 * depois do cancelamento ({@code refundLatePayment}). O banco decide na transação, com trava; o Mercado Pago é
 * chamado depois.
 */
@Service
public class CancelRefundUseCases implements PaidOrderCanceller, RefundRequester {

    /** Solicitação aguardando decisão. */
    static final String PENDING = "Há um pedido de devolução aguardando sua decisão. Decida por ele primeiro (Ver e "
            + "decidir).";

    /** Pedidos. */
    private final RefundOrders orders;

    /** Passos comuns. */
    private final RefundRecorder recorder;

    /** Envio. */
    private final RefundSender sender;

    /** Conciliação. */
    private final RefundReconciliation reconciliation;

    /** Pix pendente. */
    private final PaymentsApi payments;

    /** Histórico. */
    private final OrdersApi history;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transações. */
    private final TransactionTemplate transactions;

    /**
     * Cria os casos de uso.
     *
     * @param orders         pedidos
     * @param recorder       passos comuns
     * @param sender         envio
     * @param reconciliation conciliação
     * @param payments       pagamentos
     * @param history        histórico
     * @param events         avisos
     * @param transactions   transações
     */
    public CancelRefundUseCases(RefundOrders orders, RefundRecorder recorder, RefundSender sender,
            RefundReconciliation reconciliation, PaymentsApi payments, OrdersApi history,
            ApplicationEventPublisher events, TransactionTemplate transactions) {

        // ── 1. Dados e passos.
        this.orders = orders;
        this.recorder = recorder;
        this.sender = sender;
        this.reconciliation = reconciliation;

        // ── 2. Integrações.
        this.payments = payments;
        this.history = history;
        this.events = events;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> cancelPaidByCustomer(long orderId, CustomerId customer, String code, String note) {
        Map<String, Object> r = cancel(orderId, new Who("cliente", null, customer, true), code, note);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("state", "cancelled");
        result.put("refunded", r.get("refunded"));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> cancelByOwner(long orderId, OwnerId owner, Object code, Object note) {
        if (!Reasons.contains(Reasons.STORE_CANCEL, code)) {
            throw new InvalidInputException("Escolha o motivo do cancelamento.");
        }
        String n = NodeInput.cut(NodeInput.text(note), 300);
        if ("outro".equals(code) && n.length() < 3) {
            throw new InvalidInputException("Conte o motivo do cancelamento.");
        }
        return cancel(orderId, new Who("loja", owner, null, false), String.valueOf(code), n);
    }

    /**
     * Cancela e, se pago, devolve tudo o que resta.
     *
     * @param orderId pedido
     * @param who     quem cancela
     * @param code    motivo
     * @param note    observação
     * @return {@code ok}, {@code refundId} e {@code refunded}
     */
    public Map<String, Object> cancel(long orderId, Who who, String code, String note) {

        // ── 1. Na transação: confere, cancela e registra a devolução.
        String label = Reasons.label(code);
        Cancelled out = transactions.execute(status -> cancelInTx(orderId, who, code, note, label));

        // ── 2. Fora: desativa o Pix pendente e avisa a outra ponta antes de devolver.
        RefundOrders.OrderState o = out.order();
        if (!o.paid() && "pix".equals(o.paymentMethod())) {
            payments.cancelPendingCharges(o.paymentIds());
        }
        if ("cliente".equals(who.actor())) {
            String reason = Stream.of(label, note).filter(s -> !s.isEmpty()).collect(Collectors.joining(": "));
            events.publishEvent(new OwnerNotice.Cancelled(o.owner(), orderId, o.code(), o.contactName(), o.total(),
                    reason, out.refunded()));
        } else {
            events.publishEvent(new CustomerNotice.StoreCancel(o.ref(), label, out.refunded(), o.card()));
        }

        // ── 3. Devolve pelo Mercado Pago.
        if (out.refundId() != null) {
            sender.send(out.refundId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("refundId", out.refundId());
        result.put("refunded", NodeRows.number(out.refunded().toDecimal()));
        return result;
    }

    /**
     * As regras do cancelamento, na transação.
     *
     * @param orderId pedido
     * @param who     quem
     * @param code    motivo
     * @param note    observação
     * @param label   rótulo do motivo
     * @return o pedido, a devolução e o valor
     */
    private Cancelled cancelInTx(long orderId, Who who, String code, String note, String label) {

        // ── 1. O pedido, travado, de quem pede.
        RefundOrders.OrderState o = orders.load(orderId, true)
                .filter(x -> who.customer() == null || x.customer().equals(who.customer()))
                .filter(x -> who.owner() == null || x.owner().equals(who.owner()))
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));
        checkCancellable(o, who);

        // ── 2. Cancela as linhas e o pedido.
        orders.cancelOpenLines(orderId);
        orders.markCancelled(orderId, NodeInput.cut(label, 200), code, who.actor(), NodeInput.cut(note, 300));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("note", note);
        data.put("paid", o.paid());
        history.logEvent(orderId, who.actor(), "cancelado", data);

        // ── 3. Pago: devolve o que resta.
        Money amount = o.paid() ? o.left() : Money.ZERO;
        if (amount.cents() <= 0) {
            return new Cancelled(o, null, Money.ZERO);
        }
        String kind = switch (who.actor()) {
            case "cliente" -> "automatico";
            case "loja" -> "loja";
            default -> "sistema";
        };
        long id = recorder.approved(o, kind, code, note, amount, "", who.actor(), Map.of("automatic", true));
        return new Cancelled(o, id, amount);
    }

    /**
     * Pode cancelar? (já cancelado, solicitação aguardando, tudo devolvido, produção começou, entregue).
     *
     * @param o   pedido
     * @param who quem
     */
    private static void checkCancellable(RefundOrders.OrderState o, Who who) {

        // ── 1. Já encerrado ou com solicitação aguardando.
        boolean allCancelled = !o.statuses().isEmpty() && o.statuses().stream().allMatch("cancelado"::equals);
        if (o.cancelled() || allCancelled) {
            throw new ConflictException("Este pedido já está cancelado.");
        }
        if (o.pendingRequest()) {
            throw new ConflictException("cliente".equals(who.actor())
                    ? "Você já pediu a devolução deste pedido. Aguarde a resposta da loja." : PENDING);
        }
        if (o.paid() && !o.manual() && o.left().cents() <= 0) {
            throw new ConflictException("Todo o valor deste pedido já foi devolvido.");
        }

        // ── 2. Situação das linhas.
        if (who.onlyNew() && !o.statuses().stream().allMatch(s -> "novo".equals(s) || "cancelado".equals(s))) {
            throw new ConflictException("Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. "
                    + "Peça a devolução pelo pedido.");
        }
        if (!who.onlyNew() && !o.statuses().isEmpty()
                && o.statuses().stream().allMatch(s -> "entregue".equals(s) || "cancelado".equals(s))) {
            throw new ConflictException("Este pedido já foi entregue. Use \"Devolver valor\".");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public boolean refundLatePayment(long orderId) {

        // ── 1. Pago e cancelado, com algo a devolver.
        RefundOrders.OrderState o = orders.load(orderId, true).orElse(null);
        if (o == null || !o.paid() || !o.cancelled() || o.left().cents() <= 0) {
            return false;
        }

        // ── 2. Registra nesta transação; manda e avisa depois do commit.
        long id = recorder.approved(o, "sistema", "pago_apos_cancelamento", "Pagamento recebido depois do cancelamento",
                o.left(), "", "sistema", Map.of("why", "pago_apos_cancelamento"));
        AfterCommit.run(() -> {
            sender.send(id);
            events.publishEvent(new OwnerNotice.Refund(o.owner(), "tardio", id, o.code(), null, null, false, null, 0,
                    false));
        });
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void reconcileRefunds(long orderId, String paymentId) {
        reconciliation.reconcile(orderId, paymentId);
    }

    /**
     * Quem cancela.
     *
     * @param actor    {@code cliente}, {@code loja} ou {@code sistema}
     * @param owner    dona (confere o dono do pedido)
     * @param customer cliente (confere o dono do pedido)
     * @param onlyNew  só enquanto nenhuma linha começou
     */
    public record Who(String actor, OwnerId owner, CustomerId customer, boolean onlyNew) {
    }

    /**
     * Resultado da transação.
     *
     * @param order    pedido antes
     * @param refundId devolução (nula = nenhuma)
     * @param refunded valor
     */
    private record Cancelled(RefundOrders.OrderState order, Long refundId, Money refunded) {
    }
}
