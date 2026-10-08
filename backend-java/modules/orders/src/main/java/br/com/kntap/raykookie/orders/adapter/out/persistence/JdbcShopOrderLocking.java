package br.com.kntap.raykookie.orders.adapter.out.persistence;

import static br.com.kntap.raykookie.orders.adapter.out.persistence.JdbcManualOrders.strings;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.api.ShopOrderLocking;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Pedido da loja com trava, para a confirmação de pagamento.
 */
@Repository
public class JdbcShopOrderLocking implements ShopOrderLocking {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcShopOrderLocking(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PayableOrder> lockById(long orderId) {
        return jdbc.sql("""
                SELECT id, owner_id, customer_id, public_code, contact_name, total, paid_mp_id, paid_at IS NOT NULL,
                       COALESCE(mp_payment_ids, '{}'), cancelled_at IS NOT NULL, jsonb_array_length(items),
                       fulfillment, time_window
                  FROM shop_orders WHERE id = :id FOR UPDATE""")
                .param("id", orderId)
                .query((rs, row) -> new PayableOrder(rs.getLong(1), new OwnerId(rs.getLong(2)),
                        new CustomerId(rs.getLong(3)), rs.getInt(4), rs.getString(5), Money.fromDecimal(rs
                                .getBigDecimal(6)),
                        rs.getBoolean(8), rs.getString(7), strings(rs.getArray(9)), rs.getBoolean(10),
                        rs.getInt(11), rs.getString(12), rs.getString(13)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void markPaid(long orderId, String paymentId, Integer installments) {
        jdbc.sql("""
                UPDATE shop_orders SET paid_at = now(), paid_mp_id = :payment,
                       installments = COALESCE(CAST(:installments AS int), installments),
                       mp_payment_ids = CASE WHEN :payment = ANY(COALESCE(mp_payment_ids, '{}')) THEN mp_payment_ids
                                             ELSE array_append(mp_payment_ids, :payment) END
                 WHERE id = :id AND paid_at IS NULL""")
                .param("id", orderId).param("payment", paymentId).param("installments", installments).update();
        jdbc.sql("UPDATE orders SET payment = 'pago' WHERE shop_order_id = :id").param("id", orderId).update();
    }
}
