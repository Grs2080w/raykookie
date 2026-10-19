package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Passos comuns dentro da transação: registrar uma devolução aprovada e encerrar o pedido devolvido por inteiro
 * ({@code closeIfFullyRefunded}).
 */
@Component
public class RefundRecorder {

    /** Pedidos. */
    private final RefundOrders orders;

    /** Devoluções. */
    private final Refunds refunds;

    /** Histórico. */
    private final OrdersApi history;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria o componente.
     *
     * @param orders  pedidos
     * @param refunds devoluções
     * @param history histórico
     * @param audit   histórico de auditoria
     */
    public RefundRecorder(RefundOrders orders, Refunds refunds, OrdersApi history, AuditTrail audit) {
        this.orders = orders;
        this.refunds = refunds;
        this.history = history;
        this.audit = audit;
    }

    /**
     * Registra uma devolução já aprovada ("processando", pronta para enviar).
     *
     * @param o           pedido
     * @param kind        tipo
     * @param reasonCode  motivo
     * @param description descrição
     * @param amount      valor
     * @param note        observação da decisão
     * @param actor       quem
     * @param extra       dados extras do evento
     * @return o id
     */
    long approved(RefundOrders.OrderState o, String kind, String reasonCode, String description, Money amount,
            String note, String actor, Map<String, Object> extra) {

        // ── 1. Grava já aprovada.
        long id = refunds.insert(new Refunds.NewRefund(o.id(), o.owner(), o.customer(), kind, "processando",
                reasonCode, description, "[]", amount, amount, note, false));

        // ── 2. Registra no histórico.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", id);
        data.put("amount", NodeRows.number(amount.toDecimal()));
        data.putAll(extra);
        history.logEvent(o.id(), actor, "devolucao_aprovada", data);

        // ── 3. Auditoria: dinheiro que sai.
        audit.record(o.owner(), Entry.of("financeiro", "devolveu", "shop_order", o.id(), null,
                Map.of("refund_id", id, "amount", amount.toDecimal(), "kind", kind, "reason", reasonCode),
                "Devolução de R$ " + AuditValues.num(amount.toDecimal()) + " do pedido #" + o.code() + " (" + kind
                        + ")"));
        return id;
    }

    /**
     * Devolveu (ou vai devolver) tudo o que foi pago: as linhas não entregues viram canceladas e o pedido é encerrado
     * pelo sistema. Pedido já entregue continua "entregue".
     *
     * @param orderId pedido
     * @return verdadeiro se encerrou
     */
    boolean closeIfFullyRefunded(long orderId) {

        // ── 1. Só pedido pago, aberto, sem nada a devolver e com linha aberta.
        RefundOrders.OrderState o = orders.load(orderId, true).orElse(null);
        if (o == null || !o.paid() || o.cancelled() || o.left().cents() > 0 || orders.cancelOpenLines(orderId) == 0) {
            return false;
        }

        // ── 2. Encerra pelo sistema e registra.
        orders.markCancelled(orderId, Reasons.label(Reasons.FULL_REFUND_CODE), Reasons.FULL_REFUND_CODE, "sistema",
                "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", Reasons.FULL_REFUND_CODE);
        data.put("paid", true);
        history.logEvent(orderId, "sistema", "cancelado", data);
        return true;
    }
}
