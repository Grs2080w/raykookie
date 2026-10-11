package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.orders.api.rules.OrderStatus;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.orders.api.rules.RefundRules;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.web.NodeInput;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Pedidos do cliente no formato do Node ({@code getCustomerOrders}): situação geral, estado do pagamento, se há
 * cartão em análise no Mercado Pago, o que dá para fazer sobre devolução, as solicitações e o Pix ainda válido.
 */
@Component
public class CustomerOrderViews {

    /** Estados de devolução que já comprometem dinheiro. */
    private static final List<String> COMMITTED = List.of("aprovado", "processando", "falhou");

    /** Colunas que não vão para o cliente como vieram. */
    private static final List<String> HIDDEN = List.of("statuses", "paid_at", "pix_qr_code", "pix_qr_base64",
            "pix_expires_at", "pix_left", "delivered_at", "cancelled_at");

    /** Pedidos da loja. */
    private final ShopOrders shopOrders;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria o montador.
     *
     * @param shopOrders pedidos da loja
     * @param clock      relógio
     */
    public CustomerOrderViews(ShopOrders shopOrders, Clock clock) {
        this.shopOrders = shopOrders;
        this.clock = clock;
    }

    /**
     * Pedidos do cliente (os 100 mais novos), ou só um.
     *
     * @param customer cliente
     * @param only     só este pedido (nulo = todos)
     * @return os pedidos
     */
    public List<Map<String, Object>> of(CustomerId customer, Long only) {

        // ── 1. Pedidos e devoluções.
        List<Map<String, Object>> rows = shopOrders.customerRows(customer, only);
        List<Long> ids = rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        List<Map<String, Object>> refunds = shopOrders.refundRows(ids);

        // ── 2. O que cada pedido já tem de devolução comprometida.
        Map<Long, Money> committed = new HashMap<>();
        for (Map<String, Object> refund : refunds) {
            if (COMMITTED.contains(refund.get("status"))) {
                committed.merge(id(refund.get("shop_order_id")), money(refund.get("approved_amount")), Money::plus);
            }
        }
        Instant now = clock.instant();
        return rows.stream().map(row -> view(row, refunds, committed, now)).toList();
    }

    /**
     * Um pedido.
     *
     * @param row       linha do banco
     * @param refunds   devoluções de todos os pedidos
     * @param committed devolução comprometida por pedido
     * @param now       agora
     * @return o pedido no formato do Node
     */
    private static Map<String, Object> view(Map<String, Object> row, List<Map<String, Object>> refunds,
            Map<Long, Money> committed, Instant now) {

        // ── 1. Campos que vão como vieram.
        long id = id(row.get("id"));
        Map<String, Object> view = new LinkedHashMap<>(row);
        HIDDEN.forEach(view::remove);
        List<Map<String, Object>> mine = refunds.stream().filter(r -> id(r.get("shop_order_id")) == id).toList();

        // ── 2. Situação geral e devolução.
        List<String> statuses = statuses(row.get("statuses"));
        String status = OrderStatus.overall(statuses);
        boolean paid = row.get("paid_at") != null;
        Money total = money(row.get("total"));
        Money refunded = money(row.get("refunded_total"));
        RefundRules.Eligibility eligibility = RefundRules.eligibility(new RefundRules.Input(paid,
                instant(row.get("paid_at")), total, refunded.plus(committed.getOrDefault(id, Money.ZERO)), statuses,
                instant(row.get("delivered_at")), mine.stream().anyMatch(r -> "solicitado".equals(r.get("kind"))),
                now));

        // ── 3. Os campos calculados.
        boolean expired = row.get("pix_expires_at") != null && pixLeft(row) <= 0;
        boolean cancelled = row.get("cancelled_at") != null || "cancelado".equals(status);
        view.put("can_cancel", !statuses.isEmpty() && statuses.stream().allMatch("novo"::equals));
        view.put("status", status);
        view.put("paid", paid);
        view.put("payment_state", RefundRules.paymentState(paid, cancelled, expired, total, refunded));
        view.put("card_in_review", !paid && !cancelled && Boolean.TRUE.equals(row.get("card_in_review")));
        view.put("refund", refund(eligibility));
        view.put("cancel_reason", Objects.requireNonNullElse(row.get("cancel_reason"), ""));
        view.put("cancelled_by", row.get("cancelled_by"));
        view.put("cancel", cancel(row));
        view.put("refunds", mine.stream().map(CustomerOrderViews::refundView).toList());
        view.put("pix", pix(row, paid, status, expired));
        return view;
    }

    /**
     * O que dá para fazer sobre devolução.
     *
     * @param e elegibilidade
     * @return {@code mode}, {@code max_amount}, {@code photos_required}, {@code deadline} e {@code why_not}
     */
    private static Map<String, Object> refund(RefundRules.Eligibility e) {

        // ── 1. Modo e valor máximo.
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("mode", e.mode());
        refund.put("max_amount", NodeRows.number(e.maxAmount().toDecimal()));

        // ── 2. Fotos, prazo e motivo.
        refund.put("photos_required", e.photosRequired());
        refund.put("deadline", NodeRows.timestamp(e.deadline()));
        refund.put("why_not", e.whyNot());
        return refund;
    }

    /**
     * Dados do cancelamento, se houve.
     *
     * @param row linha
     * @return {@code by}, {@code code}, {@code reason}, {@code note} e {@code at}, ou nulo
     */
    private static Map<String, Object> cancel(Map<String, Object> row) {

        // ── 1. Só pedido cancelado.
        if (row.get("cancelled_at") == null) {
            return null;
        }

        // ── 2. Quem, por quê e quando.
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("by", Objects.requireNonNullElse(row.get("cancelled_by"), "loja"));
        cancel.put("code", Objects.requireNonNullElse(row.get("cancel_reason_code"), ""));
        cancel.put("reason", Objects.requireNonNullElse(row.get("cancel_reason"), ""));
        cancel.put("note", Objects.requireNonNullElse(row.get("cancel_note"), ""));
        cancel.put("at", row.get("cancelled_at"));
        return cancel;
    }

    /**
     * Uma solicitação de devolução como o cliente a vê (falha aparece como "processando").
     *
     * @param r linha da devolução
     * @return a solicitação
     */
    private static Map<String, Object> refundView(Map<String, Object> r) {

        // ── 1. Identificação e situação.
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", r.get("id"));
        view.put("kind", r.get("kind"));
        view.put("status", "falhou".equals(r.get("status")) ? "processando" : r.get("status"));
        view.put("label", RefundRules.customerLabel((String) r.get("status")));
        view.put("reason", Reasons.label((String) r.get("reason_code")));

        // ── 2. Valores, decisão e datas.
        view.put("requested_amount", r.get("requested_amount"));
        view.put("approved_amount", r.get("approved_amount"));
        view.put("decision_note", r.get("decision_note"));
        view.put("decision_reason", NodeInput.truthy(r.get("decision_code"))
                ? Reasons.label((String) r.get("decision_code")) : "");
        view.put("photos", r.get("photos"));
        view.put("created_at", r.get("created_at"));
        view.put("refunded_at", r.get("refunded_at"));
        return view;
    }

    /**
     * Pix a pagar: só sem pagamento, com código e não cancelado; vencido, sem o código.
     *
     * @param row     linha
     * @param paid    se foi pago
     * @param status  situação geral
     * @param expired se venceu
     * @return {@code code}, {@code qr_base64}, {@code expires_in} e {@code expired}, ou nulo
     */
    private static Map<String, Object> pix(Map<String, Object> row, boolean paid, String status, boolean expired) {

        // ── 1. Só Pix ainda a pagar.
        if (paid || !NodeInput.truthy(row.get("pix_qr_code")) || "cancelado".equals(status)) {
            return null;
        }

        // ── 2. Vencido vai sem o código.
        Map<String, Object> pix = new LinkedHashMap<>();
        pix.put("code", expired ? "" : row.get("pix_qr_code"));
        pix.put("qr_base64", expired ? "" : row.get("pix_qr_base64"));
        pix.put("expires_in", pixLeft(row));
        pix.put("expired", expired);
        return pix;
    }

    /**
     * Segundos que faltam para o Pix vencer.
     *
     * @param row linha
     * @return os segundos (0 se nulo)
     */
    private static int pixLeft(Map<String, Object> row) {
        return row.get("pix_left") instanceof Number left ? left.intValue() : 0;
    }

    /**
     * Situação de cada item, sem nulos.
     *
     * @param value lista do banco
     * @return as situações
     */
    private static List<String> statuses(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Id numérico de uma linha (int4, int8 em texto ou número).
     *
     * @param value valor
     * @return o id
     */
    private static long id(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * Valor em dinheiro de uma coluna {@code ::float8} (nulo = zero).
     *
     * @param value valor
     * @return o valor
     */
    private static Money money(Object value) {
        return value instanceof Number number ? NodeInput.money(number) : Money.ZERO;
    }

    /**
     * Instante de uma coluna já convertida para texto ISO.
     *
     * @param value valor
     * @return o instante, ou nulo
     */
    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(String.valueOf(value));
    }
}
