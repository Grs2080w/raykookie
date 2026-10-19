package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.ports.PaidOrderCanceller;
import br.com.kntap.raykookie.orders.api.ports.PendingChargeCanceller;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.orders.application.ports.OrderEvents;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O cliente cancela o próprio pedido ({@code cancelShopOrder}): só enquanto nenhum item entrou em produção. Pedido pago
 * volta o dinheiro na hora (módulo de devoluções); Pix pendente é conferido e desativado antes (módulo de pagamentos),
 * e, se tinha sido pago agora, segue como pedido pago (G09).
 */
@Service
public class CustomerCancelUseCase {

    /** Pedido de outro cliente ou inexistente. */
    static final String NOT_FOUND = "Pedido não encontrado.";

    /** Algum item já entrou em produção. */
    static final String STARTED = "Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. "
            + "Fale com a gente.";

    /** "Outro" motivo sem texto. */
    static final String TELL_REASON = "Conte o motivo do cancelamento.";

    /** Devolução sem o módulo de devoluções. */
    static final String REFUND_UNAVAILABLE = "Não foi possível cancelar agora. Tente de novo em alguns minutos.";

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerCancelUseCase.class);

    /** Pedidos da loja. */
    private final ShopOrders shopOrders;

    /** Histórico do pedido. */
    private final OrderEvents orderEvents;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transação do cancelamento. */
    private final TransactionTemplate transactions;

    /** Pix pendente (módulo de pagamentos). */
    private final ObjectProvider<PendingChargeCanceller> pending;

    /** Pedido pago (módulo de devoluções). */
    private final ObjectProvider<PaidOrderCanceller> paid;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria o caso de uso.
     *
     * @param shopOrders   pedidos da loja
     * @param orderEvents  histórico
     * @param events       avisos
     * @param transactions transações
     * @param pending      Pix pendente
     * @param paid         pedido pago
     * @param audit        histórico de auditoria
     */
    public CustomerCancelUseCase(ShopOrders shopOrders, OrderEvents orderEvents, ApplicationEventPublisher events,
            TransactionTemplate transactions, ObjectProvider<PendingChargeCanceller> pending,
            ObjectProvider<PaidOrderCanceller> paid, AuditTrail audit) {

        // ── 1. Pedidos, histórico, avisos e transações.
        this.shopOrders = shopOrders;
        this.orderEvents = orderEvents;
        this.events = events;
        this.transactions = transactions;

        // ── 2. Cobranças pendentes, pedidos pagos e auditoria.
        this.pending = pending;
        this.paid = paid;
        this.audit = audit;
    }

    /**
     * Cancela pelo corpo da rota ({@code reason_code} e {@code reason}).
     *
     * @param orderId  pedido
     * @param customer cliente
     * @param body     corpo JSON
     * @return {@code {"ok":true,"state":…}} e, se pago, {@code refunded}
     */
    public Map<String, Object> cancel(long orderId, CustomerId customer, Map<String, Object> body) {

        // ── 1. Motivo: um da lista ou "outro", que exige texto quando escolhido.
        Object chosen = body.get("reason_code");
        String code = Reasons.contains(Reasons.CANCEL, chosen) ? String.valueOf(chosen) : "outro";
        String note = NodeInput.cut(NodeInput.text(body.get("reason")), 150);
        if ("outro".equals(code) && NodeInput.truthy(chosen) && note.length() < 3) {
            throw new InvalidInputException(TELL_REASON);
        }

        // ── 2. Pedido do cliente; pago segue pela devolução.
        ShopOrders.CancelTarget target = shopOrders.cancelTarget(orderId, customer)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (target.paid() || settledNow(target)) {
            return refundAndCancel(target, customer, code, note);
        }

        // ── 3. Não pago: cancela os itens ainda novos, sob trava.
        String state = transactions.execute(status -> cancelUnpaid(target.owner(), orderId, code, note));
        if ("paid".equals(state)) {
            return refundAndCancel(target, customer, code, note);
        }
        if ("started".equals(state)) {
            throw new ConflictException(STARTED);
        }
        if ("cancelled".equals(state)) {
            events.publishEvent(new OwnerNotice.Cancelled(target.owner(), orderId, target.code(),
                    target.contactName(), target.total(), noticeReason(code, note), Money.ZERO));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("state", state);
        return result;
    }

    /**
     * Pix pendente: confere se foi pago agora e, se não foi, desativa o código. Sem resposta do Mercado Pago, o código
     * vence sozinho e o pedido é cancelado mesmo assim.
     *
     * @param target pedido
     * @return verdadeiro se o pedido acabou de ser confirmado como pago
     */
    private boolean settledNow(ShopOrders.CancelTarget target) {
        PendingChargeCanceller canceller = pending.getIfAvailable();
        if (!"pix".equals(target.paymentMethod()) || target.mpPaymentId() == null || canceller == null) {
            return false;
        }
        try {
            return canceller.settleBeforeCancel(target.id(), target.mpPaymentId());
        } catch (RuntimeException e) {
            LOG.warn("Mercado Pago sem resposta ao cancelar o Pix do pedido {}", target.id(), e);
            return false;
        }
    }

    /**
     * Pedido pago: cancela devolvendo o dinheiro.
     *
     * @param target   pedido
     * @param customer cliente
     * @param code     motivo
     * @param note     texto do cliente
     * @return o resultado do módulo de devoluções
     */
    private Map<String, Object> refundAndCancel(ShopOrders.CancelTarget target, CustomerId customer, String code,
            String note) {
        PaidOrderCanceller canceller = paid.getIfAvailable();
        if (canceller == null) {
            throw new DomainException(REFUND_UNAVAILABLE, 503);
        }
        return canceller.cancelPaidByCustomer(target.id(), customer, code, note);
    }

    /**
     * Cancela, na transação, se nenhum item entrou em produção.
     *
     * @param owner   dona da loja
     * @param orderId pedido
     * @param code    motivo
     * @param note    texto do cliente
     * @return {@code cancelled}, {@code already}, {@code started} ou {@code paid} (pago no meio do caminho)
     */
    private String cancelUnpaid(OwnerId owner, long orderId, String code, String note) {

        // ── 1. Trava o pedido e os itens (a confirmação de pagamento trava na mesma ordem); pago agora: devolução.
        if (shopOrders.lockPaid(orderId)) {
            return "paid";
        }
        List<String> statuses = shopOrders.lockStatuses(orderId);
        if (!statuses.isEmpty() && statuses.stream().allMatch("cancelado"::equals)) {
            return "already";
        }
        if (!statuses.stream().allMatch(s -> "novo".equals(s) || "cancelado".equals(s))) {
            return "started";
        }

        // ── 2. Cancela e registra no histórico.
        shopOrders.cancelByCustomer(orderId, NodeInput.cut(Reasons.label(code), 200), code,
                NodeInput.cut(note, 300));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("note", note);
        data.put("paid", false);
        orderEvents.log(orderId, "cliente", "cancelado", data);
        audit.record(owner, Entry.of("pedido", "cancelou", "shop_order", orderId,
                Map.of("cancelled", false, "paid", false),
                Map.of("cancelled", true, "by", "cliente", "reason", Reasons.label(code), "note", note),
                "Pedido #" + orderId + " cancelado pelo cliente: " + Reasons.label(code)));
        return "cancelled";
    }

    /**
     * Motivo no aviso à dona: o rótulo e o texto do cliente ("outro" com texto mostra só o texto).
     *
     * @param code motivo
     * @param note texto do cliente
     * @return o motivo
     */
    static String noticeReason(String code, String note) {
        String label = "outro".equals(code) && !note.isEmpty() ? "" : Reasons.label(code);
        return Stream.of(label, note).filter(s -> !s.isEmpty()).collect(Collectors.joining(": "));
    }
}
