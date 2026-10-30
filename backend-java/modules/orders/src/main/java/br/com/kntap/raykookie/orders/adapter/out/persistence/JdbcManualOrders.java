package br.com.kntap.raykookie.orders.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.ManualOrders;
import br.com.kntap.raykookie.orders.domain.ManualOrderForm;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Linhas da tela Pedidos com o mesmo SQL de {@code backend/lib/queries.ts} ({@code getOrders}) e de
 * {@code /api/orders}.
 */
@Repository
public class JdbcManualOrders implements ManualOrders {

    /** Pedidos da dona com os dados do pedido da loja, cancelamento e a devolução mais recente. */
    private static final String LIST = """
            SELECT o.*, so.public_code AS shop_code, (so.paid_at IS NOT NULL) AS shop_paid, so.total::float8 AS
                shop_total,
                   so.refunded_total::float8 AS shop_refunded, so.payment_method AS shop_payment_method,
                   so.installments AS shop_installments,
                   CASE WHEN so.cancelled_at IS NOT NULL THEN json_build_object('by', so.cancelled_by,
                        'code', so.cancel_reason_code, 'reason', so.cancel_reason, 'note', so.cancel_note) END AS
                            shop_cancel,
                   (SELECT json_build_object('id', r.id, 'kind', r.kind, 'status', r.status, 'reason_code',
                       r.reason_code,
                           'requested_amount', r.requested_amount::float8, 'approved_amount', r.approved_amount::float8,
                           'fail_reason', r.fail_reason, 'decision_code', r.decision_code,
                           'photos', (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id))
                      FROM refunds r WHERE r.shop_order_id = so.id AND r.status <> 'desistiu'
                     ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS shop_refund
              FROM orders o LEFT JOIN shop_orders so ON so.id = o.shop_order_id
             WHERE o.user_id = :owner ORDER BY o.order_date DESC, o.number DESC""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcManualOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql(LIST).param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, int number, ManualOrderForm form, UUID group) {
        return params(jdbc.sql("""
                INSERT INTO orders (user_id, number, order_date, customer, recipe_id, product, qty, unit_price,
                                    delivery_date, status, payment, notes, group_id)
                VALUES (:owner, :number, :orderDate, :customer, :recipe, :product, :qty, :price, :delivery, :status,
                        :payment, :notes, :group)
                RETURNING *"""), form).param("owner", owner.value()).param("number", number)
                .param("group", group).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> groupLines(long id, OwnerId owner) {
        return jdbc.sql("""
                SELECT x.id FROM orders o JOIN orders x ON x.user_id = o.user_id
                       AND (x.id = o.id OR (o.group_id IS NOT NULL AND x.group_id = o.group_id))
                 WHERE o.id = :id AND o.user_id = :owner ORDER BY x.id""")
                .param("id", id).param("owner", owner.value()).query(Long.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public void setGroup(List<Long> ids, OwnerId owner, UUID group) {
        jdbc.sql("UPDATE orders SET group_id = :group WHERE user_id = :owner AND id IN (:ids)")
                .param("group", group).param("owner", owner.value()).param("ids", ids).update();
    }

    /**
     * Preenche os campos do formulário.
     *
     * @param spec comando
     * @param form dados
     * @return o comando com os parâmetros
     */
    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, ManualOrderForm form) {
        return spec.param("orderDate", form.orderDate()).param("customer", form.customer())
                .param("recipe", form.recipeId()).param("product", form.product()).param("qty", form.qty())
                .param("price", form.unitPrice()).param("delivery", form.deliveryDate()).param("status", form.status())
                .param("payment", form.payment()).param("notes", form.notes());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AppLine> appLine(long id, OwnerId owner) {
        return jdbc.sql("""
                SELECT o.qty, o.unit_price, o.product, o.status, (so.cancelled_at IS NOT NULL) AS closed
                  FROM orders o JOIN shop_orders so ON so.id = o.shop_order_id
                 WHERE o.id = :id AND o.user_id = :owner""")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> new AppLine(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> quickUpdate(long id, OwnerId owner, String status, String payment) {
        return jdbc.sql("""
                UPDATE orders SET status = COALESCE(CAST(:status AS text), status),
                                  payment = COALESCE(CAST(:payment AS text), payment)
                 WHERE id = :id AND user_id = :owner
                   AND NOT (shop_order_id IS NOT NULL AND CAST(:status AS text) IS NOT DISTINCT FROM 'cancelado')
                RETURNING *""")
                .param("status", status).param("payment", payment).param("id", id).param("owner", owner.value())
                .query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(long id, OwnerId owner) {
        return jdbc.sql("SELECT 1 FROM orders WHERE id = :id AND user_id = :owner").param("id", id)
                .param("owner", owner.value()).query(Integer.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(long id, OwnerId owner, ManualOrderForm form) {
        return params(jdbc.sql("""
                UPDATE orders SET order_date = :orderDate, customer = :customer, recipe_id = :recipe, product =
                    :product,
                       qty = :qty, unit_price = :price, delivery_date = :delivery, status = :status, payment = :payment,
                       notes = :notes
                 WHERE id = :id AND user_id = :owner RETURNING *"""), form)
                .param("id", id).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void followManualShopOrder(long id, ManualOrderForm form) {
        jdbc.sql("""
                UPDATE shop_orders SET scheduled_for = COALESCE(CAST(:delivery AS date), scheduled_for),
                       contact_name = :customer
                 WHERE id = (SELECT shop_order_id FROM orders WHERE id = :id) AND manual""")
                .param("delivery", form.deliveryDate()).param("customer", form.customer()).param("id", id).update();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long id, OwnerId owner) {
        jdbc.sql("DELETE FROM orders WHERE id = :id AND user_id = :owner").param("id", id)
                .param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Snapshot> snapshot(long id, OwnerId owner) {
        return jdbc.sql("""
                SELECT o.shop_order_id,
                       (SELECT array_agg(x.status) FROM orders x WHERE x.shop_order_id = o.shop_order_id) AS statuses
                  FROM orders o WHERE o.id = :id AND o.user_id = :owner AND o.shop_order_id IS NOT NULL""")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> new Snapshot(rs.getLong(1), strings(rs.getArray(2)))).optional();
    }

    /**
     * Array de texto do Postgres.
     *
     * @param array o array (nulo = vazio)
     * @return a lista
     * @throws SQLException se a leitura falhar
     */
    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
