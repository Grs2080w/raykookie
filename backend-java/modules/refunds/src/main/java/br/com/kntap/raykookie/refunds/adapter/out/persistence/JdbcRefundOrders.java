package br.com.kntap.raykookie.refunds.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import br.com.kntap.raykookie.web.NodeInput;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O pedido visto pelas devoluções, com o SQL de {@code loadOrder} e de {@code cancelCore}.
 */
@Repository
public class JdbcRefundOrders implements RefundOrders {

    /** JSON dos itens. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcRefundOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<OrderState> load(long orderId, boolean lock) {

        // ── 1. O pedido (e as linhas) com trava, se pedida.
        String forUpdate = lock ? " FOR UPDATE" : "";
        Optional<Base> base = jdbc.sql("""
                SELECT id, owner_id, customer_id, public_code, contact_name, total, paid_at, paid_mp_id, mp_payment_ids,
                       cancelled_at IS NOT NULL AS cancelled, items::text AS items, refunded_total, delivered_at,
                       payment_method, manual, fulfillment, time_window
                  FROM shop_orders WHERE id = :id""" + forUpdate).param("id", orderId)
                .query((rs, row) -> new Base(rs.getLong("id"), new OwnerId(rs.getLong("owner_id")),
                        new CustomerId(rs.getLong("customer_id")), rs.getInt("public_code"), rs
                                .getString("contact_name"),
                        Jdbc.money(rs, "total"), Jdbc.instant(rs, "paid_at"), rs.getString("paid_mp_id"),
                        strings(rs.getArray("mp_payment_ids")), rs.getBoolean("cancelled"), items(rs
                                .getString("items")),
                        Jdbc.money(rs, "refunded_total"), Jdbc.instant(rs, "delivered_at"),
                        rs.getString("payment_method"), rs.getBoolean("manual"), rs.getString("fulfillment"),
                        rs.getString("time_window")))
                .optional();
        if (base.isEmpty()) {
            return Optional.empty();
        }

        // ── 2. Linhas, solicitações e o que já está comprometido.
        List<String> statuses = jdbc.sql("SELECT status FROM orders WHERE shop_order_id = :id ORDER BY id" + forUpdate)
                .param("id", orderId).query(String.class).list();
        List<String> asks = jdbc.sql("""
                SELECT status FROM refunds
                 WHERE shop_order_id = :id AND kind = 'solicitado' AND status <> 'desistiu'""")
                .param("id", orderId).query(String.class).list();
        Money committed = Money.fromDecimal(jdbc.sql("""
                SELECT COALESCE(sum(approved_amount), 0) FROM refunds
                 WHERE shop_order_id = :id AND status IN ('aprovado', 'processando', 'falhou')""")
                .param("id", orderId).query(java.math.BigDecimal.class).single());
        Base b = base.get();
        return Optional.of(new OrderState(b.id(), b.owner(), b.customer(), b.code(), b.contactName(), b.total(),
                b.paidAt(), b.paidMpId(), b.paymentIds(), b.cancelled(), b.items(), b.refundedTotal(), b.deliveredAt(),
                b.paymentMethod(), b.manual(), statuses, !asks.isEmpty(), asks.contains("solicitado"), committed,
                b.fulfillment(), b.timeWindow()));
    }

    /**
     * Itens do pedido ({@code [{id, name, qty, unit_price}]}).
     *
     * @param json JSON
     * @return os itens
     */
    static List<RefundRequestForm.OrderedItem> items(String json) {
        List<RefundRequestForm.OrderedItem> items = new ArrayList<>();
        for (JsonNode item : JSON.readTree(json)) {
            items.add(new RefundRequestForm.OrderedItem(item.path("id").asString(), item.path("name").asString(),
                    item.path("qty").asLong(), NodeInput.money(item.path("unit_price").numberValue())));
        }
        return items;
    }

    /**
     * Array de texto como lista.
     *
     * @param array array
     * @return a lista
     * @throws SQLException se a leitura falhar
     */
    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of()
                : Arrays.stream((String[]) array.getArray()).filter(Objects::nonNull).toList();
    }

    /** {@inheritDoc} */
    @Override
    public int cancelOpenLines(long orderId) {
        return jdbc.sql("""
                UPDATE orders SET status = 'cancelado'
                 WHERE shop_order_id = :id AND status <> 'cancelado' AND status <> 'entregue'""")
                .param("id", orderId).update();
    }

    /** {@inheritDoc} */
    @Override
    public void markCancelled(long orderId, String label, String code, String actor, String note) {
        jdbc.sql("""
                UPDATE shop_orders SET cancelled_at = now(), cancel_reason = :label, cancel_reason_code = :code,
                       cancelled_by = :actor, cancel_note = :note
                 WHERE id = :id""")
                .param("id", orderId).param("label", label).param("code", code).param("actor", actor)
                .param("note", note).update();
    }

    /** {@inheritDoc} */
    @Override
    public void addRefunded(long orderId, Money amount) {
        jdbc.sql("UPDATE shop_orders SET refunded_total = LEAST(total, refunded_total + :amount) WHERE id = :id")
                .param("id", orderId).param("amount", amount.toDecimal()).update();
    }

    /**
     * Colunas do pedido.
     *
     * @param id            pedido
     * @param owner         dona
     * @param customer      cliente
     * @param code          número
     * @param contactName   nome
     * @param total         total
     * @param paidAt        pago em
     * @param paidMpId      pagamento
     * @param paymentIds    pagamentos
     * @param cancelled     cancelado
     * @param items         itens
     * @param refundedTotal devolvido
     * @param deliveredAt   entregue em
     * @param paymentMethod forma de pagamento
     * @param manual        registrado à mão
     * @param fulfillment   retirada ou entrega
     * @param timeWindow    período
     */
    private record Base(long id, OwnerId owner, CustomerId customer, int code, String contactName, Money total,
            java.time.Instant paidAt, String paidMpId, List<String> paymentIds, boolean cancelled,
            List<RefundRequestForm.OrderedItem> items, Money refundedTotal, java.time.Instant deliveredAt,
            String paymentMethod, boolean manual, String fulfillment, String timeWindow) {
    }
}
