package br.com.kntap.raykookie.refunds.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Devoluções e fotos, com o SQL de {@code backend/lib/refunds.ts}.
 */
@Repository
public class JdbcRefunds implements Refunds {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcRefunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public long insert(NewRefund r) {
        return jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, description,
                    items,
                                     requested_amount, approved_amount, decision_note, decided_at, priority)
                VALUES (:order, :owner, :customer, :kind, :status, :reason, :description, CAST(:items AS jsonb),
                        :requested, :approved, :note, CASE WHEN CAST(:approved AS numeric) IS NULL THEN NULL ELSE
                            now() END,
                        :priority)
                RETURNING id""")
                .param("order", r.orderId()).param("owner", r.owner().value()).param("customer", r.customer().value())
                .param("kind", r.kind()).param("status", r.status()).param("reason", r.reasonCode())
                .param("description", r.description()).param("items", r.itemsJson())
                .param("requested", r.requested().toDecimal())
                .param("approved", r.approved() == null ? null : r.approved().toDecimal())
                .param("note", r.decisionNote()).param("priority", r.priority()).query(Long.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public void insertPhotos(long refundId, List<RefundRequestForm.Photo> photos) {
        for (int i = 0; i < photos.size(); i++) {
            RefundRequestForm.Photo p = photos.get(i);
            jdbc.sql("""
                    INSERT INTO refund_photos (refund_id, idx, content_type, data, size)
                    VALUES (:refund, :idx, :type, :data, :size)""")
                    .param("refund", refundId).param("idx", i + 1).param("type", p.type()).param("data", p.data())
                    .param("size", p.data().length).update();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Claimed> claim(long refundId) {
        Optional<Long> locked = jdbc.sql("""
                SELECT id FROM refunds
                 WHERE id = :id AND status IN ('aprovado', 'processando', 'falhou') AND approved_amount > 0
                 FOR UPDATE SKIP LOCKED""").param("id", refundId).query(Long.class).optional();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                UPDATE refunds SET status = 'processando', attempts = attempts + 1, next_try_at = NULL WHERE id = :id
                RETURNING id, shop_order_id, approved_amount, attempts""")
                .param("id", refundId)
                .query((rs, row) -> new Claimed(rs.getLong(1), rs.getLong(2), Jdbc.money(rs, "approved_amount"),
                        rs.getInt(4)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public boolean finishSent(long refundId, boolean done, String mpRefundId, String mpStatus) {
        return jdbc.sql("""
                UPDATE refunds SET status = :status, mp_refund_id = :mp, mp_status = :mpStatus, fail_reason = NULL,
                       next_try_at = NULL, refunded_at = CASE WHEN :status = 'concluido' THEN now() ELSE refunded_at END
                 WHERE id = :id AND status <> 'concluido'""")
                .param("id", refundId).param("status", done ? "concluido" : "processando").param("mp", mpRefundId)
                .param("mpStatus", mpStatus).update() > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void markFailed(long refundId, String why, Integer waitMinutes) {
        jdbc.sql("""
                UPDATE refunds SET status = 'falhou', fail_reason = :why,
                       next_try_at = CASE WHEN CAST(:wait AS int) IS NULL THEN NULL
                                          ELSE now() + make_interval(mins => CAST(:wait AS int)) END
                 WHERE id = :id""").param("id", refundId).param("why", why).param("wait", waitMinutes).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> due(int maxAttempts) {
        return jdbc.sql("""
                SELECT id FROM refunds
                 WHERE (status = 'falhou' AND next_try_at IS NOT NULL AND next_try_at <= now())
                    OR (status = 'processando' AND mp_refund_id IS NULL AND refunded_at IS NULL
                        AND created_at < now() - interval '10 minutes' AND attempts < :max)
                 ORDER BY id LIMIT 20""").param("max", maxAttempts).query(Long.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Withdrawn> withdraw(long orderId, CustomerId customer) {
        return jdbc.sql("""
                UPDATE refunds r SET status = 'desistiu', decided_at = now() FROM shop_orders so
                 WHERE so.id = r.shop_order_id AND r.shop_order_id = :order AND r.customer_id = :customer
                   AND r.kind = 'solicitado' AND r.status = 'solicitado'
                RETURNING r.id, so.owner_id, so.public_code, so.contact_name""")
                .param("order", orderId).param("customer", customer.value())
                .query((rs, row) -> new Withdrawn(rs.getLong(1), new OwnerId(rs.getLong(2)), rs.getInt(3),
                        rs.getString(4)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RefundRow> lock(long refundId, OwnerId owner) {
        return jdbc.sql("""
                SELECT id, shop_order_id, customer_id, status, requested_amount FROM refunds
                 WHERE id = :id AND owner_id = :owner FOR UPDATE""")
                .param("id", refundId).param("owner", owner.value())
                .query((rs, row) -> new RefundRow(rs.getLong(1), rs.getLong(2), new CustomerId(rs.getLong(3)),
                        rs.getString(4), Jdbc.money(rs, "requested_amount")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void approve(long refundId, Money amount, String note) {
        jdbc.sql("""
                UPDATE refunds SET status = 'aprovado', approved_amount = :amount, decision_note = :note,
                       decided_at = now()
                 WHERE id = :id""").param("id", refundId).param("amount", amount.toDecimal()).param("note", note)
                .update();
    }

    /** {@inheritDoc} */
    @Override
    public void deny(long refundId, String code, String note) {
        jdbc.sql("""
                UPDATE refunds SET status = 'negado', decision_code = :code, decision_note = :note, decided_at = now()
                 WHERE id = :id""").param("id", refundId).param("code", code).param("note", note).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> status(long refundId, OwnerId owner) {
        return jdbc.sql("SELECT status FROM refunds WHERE id = :id AND owner_id = :owner").param("id", refundId)
                .param("owner", owner.value()).query(String.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Known> byMpRefundId(String mpRefundId) {
        return jdbc.sql("SELECT id, status FROM refunds WHERE mp_refund_id = :mp").param("mp", mpRefundId)
                .query((rs, row) -> new Known(rs.getLong(1), rs.getString(2))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void complete(long refundId) {
        jdbc.sql("""
                UPDATE refunds SET status = 'concluido', mp_status = 'approved', refunded_at = now(), fail_reason =
                    NULL,
                       next_try_at = NULL
                 WHERE id = :id""").param("id", refundId).update();
    }

    /** {@inheritDoc} */
    @Override
    public void insertExternal(long orderId, OwnerId owner, CustomerId customer, Money amount, String mpRefundId) {
        jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, description,
                                     requested_amount, approved_amount, decision_note, decided_at, mp_refund_id,
                                     mp_status, refunded_at)
                VALUES (:order, :owner, :customer, 'loja', 'concluido', 'outro', 'Devolução feita direto no Mercado
                    Pago',
                        :amount, :amount, 'Feita no Mercado Pago', now(), :mp, 'approved', now())""")
                .param("order", orderId).param("owner", owner.value()).param("customer", customer.value())
                .param("amount", amount.toDecimal()).param("mp", mpRefundId).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Processing> processing() {
        return jdbc.sql("""
                SELECT DISTINCT r.shop_order_id, so.paid_mp_id FROM refunds r JOIN shop_orders so ON so.id =
                    r.shop_order_id
                 WHERE r.status = 'processando' AND r.mp_refund_id IS NOT NULL AND so.paid_mp_id IS NOT NULL""")
                .query((rs, row) -> new Processing(rs.getLong(1), rs.getString(2))).list();
    }

    /** {@inheritDoc} */
    @Override
    public int cleanupOldPhotos() {
        return jdbc.sql("""
                DELETE FROM refund_photos WHERE refund_id IN (
                  SELECT id FROM refunds WHERE status IN ('concluido', 'negado', 'desistiu')
                     AND COALESCE(decided_at, refunded_at, created_at) < now() - interval '180 days')""").update();
    }
}
