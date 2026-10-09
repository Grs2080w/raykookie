package br.com.kntap.raykookie.refunds.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundQueries;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Leituras das telas de devolução, com o SQL das rotas {@code devolucoes/**} do Node.
 */
@Repository
public class JdbcRefundQueries implements RefundQueries {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcRefundQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner, List<String> statuses) {
        return jdbc.sql("""
                SELECT r.id, r.shop_order_id AS order_id, so.public_code AS code, so.contact_name AS customer, r.kind,
                       r.status, r.reason_code, r.priority, r.requested_amount::float8 AS requested_amount,
                       r.approved_amount::float8 AS approved_amount, r.fail_reason, r.created_at,
                       (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id) AS photos
                  FROM refunds r JOIN shop_orders so ON so.id = r.shop_order_id
                 WHERE r.owner_id = :owner AND r.status IN (:statuses)
                 ORDER BY r.priority DESC, r.created_at DESC LIMIT 100""")
                .param("owner", owner.value()).param("statuses", statuses).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Integer> counts(OwnerId owner) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*)::int FROM refunds WHERE owner_id = :owner GROUP BY status")
                .param("owner", owner.value()).query((rs, row) -> counts.put(rs.getString(1), rs.getInt(2))).list();
        return counts;
    }

    /** {@inheritDoc} */
    @Override
    public Object pendingAmount(OwnerId owner) {
        return NodeRows.number(jdbc.sql("""
                SELECT COALESCE(sum(approved_amount), 0)::float8 FROM refunds
                 WHERE owner_id = :owner AND status IN ('aprovado', 'processando', 'falhou')""")
                .param("owner", owner.value()).query(Double.class).single());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> detail(long refundId, OwnerId owner) {
        return jdbc.sql("""
                SELECT r.id, r.shop_order_id AS order_id, so.public_code AS code, so.contact_name AS customer, so.phone,
                       so.total::float8 AS order_total, so.refunded_total::float8 AS refunded_total,
                       so.items AS order_items, so.delivered_at, so.paid_at, r.kind, r.status, r.reason_code,
                       r.description, r.items, r.priority, r.requested_amount::float8 AS requested_amount,
                       r.approved_amount::float8 AS approved_amount, r.decision_code, r.decision_note, r.decided_at,
                       r.fail_reason, r.attempts, r.next_try_at, r.created_at, r.refunded_at,
                       (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses
                  FROM refunds r JOIN shop_orders so ON so.id = r.shop_order_id
                 WHERE r.id = :id AND r.owner_id = :owner""")
                .param("id", refundId).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public List<Integer> photoIndexes(long refundId) {
        return jdbc.sql("SELECT idx FROM refund_photos WHERE refund_id = :id ORDER BY idx").param("id", refundId)
                .query(Integer.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Photo> ownerPhoto(long refundId, OwnerId owner, int n) {
        return jdbc.sql("""
                SELECT p.content_type, p.data FROM refund_photos p JOIN refunds r ON r.id = p.refund_id
                 WHERE r.id = :id AND r.owner_id = :owner AND p.idx = :n""")
                .param("id", refundId).param("owner", owner.value()).param("n", n)
                .query((rs, row) -> new Photo(rs.getString(1), rs.getBytes(2))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Photo> customerPhoto(long refundId, CustomerId customer, int n) {
        return jdbc.sql("""
                SELECT p.content_type, p.data FROM refund_photos p JOIN refunds r ON r.id = p.refund_id
                 WHERE r.id = :id AND r.customer_id = :customer AND p.idx = :n""")
                .param("id", refundId).param("customer", customer.value()).param("n", n)
                .query((rs, row) -> new Photo(rs.getString(1), rs.getBytes(2))).optional();
    }
}
