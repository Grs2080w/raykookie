package br.com.kntap.raykookie.payments.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.persistence.Jdbc;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Colunas de cobrança de {@code shop_orders}, com o SQL de {@code pix.ts}, {@code card-pay.ts} e
 * {@code reconcilePending}.
 */
@Repository
public class JdbcOrderCharges implements OrderCharges {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcOrderCharges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ChargeTarget> target(long orderId, CustomerId customer) {
        return jdbc.sql("""
                SELECT so.id, so.owner_id, so.public_code, so.total, so.contact_name, c.email, so.phone,
                       so.payment_method,
                       so.paid_at IS NOT NULL AS paid, so.mp_payment_id, so.pix_qr_code, so.pix_qr_base64,
                       so.pix_expires_at, so.pix_attempts, so.card_attempts,
                       (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses,
                       jsonb_array_length(so.items) AS items
                  FROM shop_orders so JOIN customers c ON c.id = so.customer_id
                 WHERE so.id = :id AND so.customer_id = :customer""")
                .param("id", orderId).param("customer", customer.value())
                .query((rs, row) -> new ChargeTarget(rs.getLong("id"), new OwnerId(rs.getLong("owner_id")),
                        rs.getInt("public_code"), Jdbc.money(rs, "total"), rs.getString("contact_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("payment_method"),
                        rs.getBoolean("paid"),
                        rs.getString("mp_payment_id"), rs.getString("pix_qr_code"), rs.getString("pix_qr_base64"),
                        Jdbc.instant(rs, "pix_expires_at"), rs.getInt("pix_attempts"), rs.getInt("card_attempts"),
                        strings(rs.getArray("statuses")), rs.getInt("items")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void savePix(long orderId, String paymentId, String code, String qrBase64, Instant expiresAt,
            int attempt) {
        jdbc.sql("""
                UPDATE shop_orders SET mp_payment_id = :payment, pix_qr_code = :code, pix_qr_base64 = :qr,
                       pix_expires_at = :expires, pix_attempts = :attempt,
                       mp_payment_ids = array_append(mp_payment_ids, :payment)
                 WHERE id = :id""")
                .param("id", orderId).param("payment", paymentId).param("code", code).param("qr", qrBase64)
                .param("expires", Jdbc.timestamp(expiresAt)).param("attempt", attempt).update();
    }

    /** {@inheritDoc} */
    @Override
    public void addPaymentId(long orderId, String paymentId) {
        jdbc.sql("""
                UPDATE shop_orders SET mp_payment_ids = array_append(mp_payment_ids, :payment)
                 WHERE id = :id AND NOT (:payment = ANY(mp_payment_ids))""")
                .param("id", orderId).param("payment", paymentId).update();
    }

    /** {@inheritDoc} */
    @Override
    public void countCardAttempt(long orderId, String paymentId) {
        jdbc.sql("""
                UPDATE shop_orders SET card_attempts = card_attempts + 1,
                       mp_payment_ids = CASE WHEN CAST(:payment AS text) IS NULL THEN mp_payment_ids
                                             ELSE array_append(mp_payment_ids, :payment) END
                 WHERE id = :id""").param("id", orderId).param("payment", paymentId).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<OpenCharge> openCharges() {
        return jdbc.sql("""
                SELECT id, mp_payment_ids FROM shop_orders
                 WHERE paid_at IS NULL AND created_at > now() - interval '2 days' AND cardinality(mp_payment_ids) > 0
                 ORDER BY id""")
                .query((rs, row) -> new OpenCharge(rs.getLong(1), strings(rs.getArray(2)))).list();
    }

    /**
     * Array de texto do Postgres como lista (nulo = vazia).
     *
     * @param array array
     * @return a lista
     * @throws SQLException se a leitura falhar
     */
    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }
}
