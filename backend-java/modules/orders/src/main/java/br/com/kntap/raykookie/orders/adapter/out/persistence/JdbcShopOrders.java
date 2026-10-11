package br.com.kntap.raykookie.orders.adapter.out.persistence;

import static br.com.kntap.raykookie.orders.adapter.out.persistence.JdbcManualOrders.strings;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Pedidos da loja com o mesmo SQL de {@code backend/lib/shop-queries.ts} e de {@code backend/lib/cancel.ts}.
 */
@Repository
public class JdbcShopOrders implements ShopOrders {

    /** JSON dos itens. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Pedidos do cliente ({@code getCustomerOrders}). */
    private static final String CUSTOMER_ROWS = """
            SELECT so.id, so.public_code AS code, so.pickup_address, so.created_at, so.scheduled_for, so.time_window,
                   so.fulfillment, so.street, so.number, so.complement, so.district, so.city, so.payment_method,
                   so.installments, so.notes, so.total::float8 AS total, so.items, so.paid_at,
                   so.refunded_total::float8 AS refunded_total, so.delivered_at, so.cancelled_at, so.cancelled_by,
                   so.cancel_reason, so.cancel_reason_code, so.cancel_note, so.pix_qr_code, so.pix_qr_base64,
                   so.pix_expires_at, so.manual,
                   GREATEST(0, floor(extract(epoch from (so.pix_expires_at - now()))))::int AS pix_left,
                   (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses,
                   EXISTS (SELECT 1 FROM payment_attempts pa WHERE pa.shop_order_id = so.id
                              AND pa.state IN ('in_flight', 'pending')
                              AND pa.created_at > now() - interval '7 days') AS card_in_review
              FROM shop_orders so
             WHERE so.customer_id = :customer AND (CAST(:only AS int) IS NULL OR so.id = :only)
             ORDER BY so.created_at DESC, so.id DESC LIMIT 100""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcShopOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public boolean codeTaken(int code) {
        return jdbc.sql("SELECT 1 FROM shop_orders WHERE public_code = :code").param("code", code).query(Integer.class)
                .optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public long insert(NewShopOrder order) {
        ShopOrderForm form = order.form();
        ShopOrderForm.Address address = form.address();
        return jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, cep, street, number,
                       complement, district, city, scheduled_for, time_window, payment_method, change_for, notes, items,
                       total, public_code, pickup_address)
                VALUES (:customer, :owner, :name, :phone, :fulfillment, :cep, :street, :number, :complement, :district,
                        :city, :scheduled, :window, :payment, :change, :notes, CAST(:items AS jsonb), :total, :code,
                        :pickup)
                RETURNING id""")
                .param("customer", order.customer().value()).param("owner", order.owner().value())
                .param("name", form.contactName()).param("phone", form.phone()).param("fulfillment", form.fulfillment())
                .param("cep", address.cep()).param("street", address.street()).param("number", address.number())
                .param("complement", address.complement()).param("district", address.district())
                .param("city", address.city()).param("scheduled", form.scheduledFor()).param("window", form
                        .timeWindow())
                .param("payment", form.paymentMethod())
                .param("change", form.changeFor() == null ? null : form.changeFor().toDecimal())
                .param("notes", form.notes()).param("items", itemsJson(form.items()))
                .param("total", form.total().toDecimal()).param("code", order.code()).param("pickup", order.pickup())
                .query(Long.class).single();
    }

    /**
     * Itens como o Node grava ({@code [{id, name, qty, unit_price}]}, números como no JavaScript).
     *
     * @param items itens
     * @return o JSON
     */
    static String itemsJson(List<ShopOrderForm.Line> items) {
        return JSON.writeValueAsString(items.stream().map(line -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", line.id());
            json.put("name", line.name());
            json.put("qty", line.qty());
            json.put("unit_price", NodeRows.number(line.unitPrice().toDecimal()));
            return json;
        }).toList());
    }

    /** {@inheritDoc} */
    @Override
    public void insertLine(NewLine line) {
        jdbc.sql("""
                INSERT INTO orders (user_id, number, customer, recipe_id, combo_id, product, qty, unit_price,
                                    delivery_date, status, payment, notes, address, shop_order_id)
                VALUES (:owner, :number, :customer, :recipe, :combo, :product, :qty, :price, :delivery, 'novo',
                        'pendente', :notes, :address, :shopOrder)""")
                .param("owner", line.owner().value()).param("number", line.number()).param("customer", line.customer())
                .param("recipe", line.line().recipeId()).param("combo", line.line().comboId())
                .param("product", line.line().name())
                .param("qty", line.line().qty()).param("price", line.line().unitPrice().toDecimal())
                .param("delivery", line.delivery()).param("notes", line.notes()).param("address", line.address())
                .param("shopOrder", line.shopOrderId()).update();
    }

    /** {@inheritDoc} */
    @Override
    public void rememberContact(CustomerId customer, ShopOrderForm form) {
        ShopOrderForm.Address a = form.address();
        jdbc.sql("""
                UPDATE customers SET phone = :phone,
                       cep = CASE WHEN :cep = '' THEN cep ELSE :cep END,
                       street = CASE WHEN :cep = '' THEN street ELSE :street END,
                       number = CASE WHEN :cep = '' THEN number ELSE :number END,
                       complement = CASE WHEN :cep = '' THEN complement ELSE :complement END,
                       district = CASE WHEN :cep = '' THEN district ELSE :district END,
                       city = CASE WHEN :cep = '' THEN city ELSE :city END
                 WHERE id = :id""")
                .param("id", customer.value()).param("phone", form.phone()).param("cep", a.cep())
                .param("street", a.street()).param("number", a.number()).param("complement", a.complement())
                .param("district", a.district()).param("city", a.city()).update();
    }

    /** {@inheritDoc} */
    @Override
    public int openUnpaidOnline(CustomerId customer) {
        return jdbc.sql("""
                SELECT count(*)::int FROM shop_orders
                 WHERE customer_id = :customer AND payment_method IN ('pix', 'cartao') AND paid_at IS NULL
                   AND cancelled_at IS NULL AND created_at > now() - interval '24 hours'""")
                .param("customer", customer.value()).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> customerRows(CustomerId customer, Long only) {
        return jdbc.sql(CUSTOMER_ROWS).param("customer", customer.value()).param("only", only).query(NodeRows.ROW)
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> refundRows(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT r.id, r.shop_order_id, r.kind, r.status, r.reason_code, r.requested_amount::float8 AS
                    requested_amount,
                       r.approved_amount::float8 AS approved_amount, r.decision_note, r.decision_code, r.created_at,
                       r.refunded_at, (SELECT count(*)::int FROM refund_photos p WHERE p.refund_id = r.id) AS photos
                  FROM refunds r WHERE r.shop_order_id IN (:ids) AND r.status <> 'desistiu'
                 ORDER BY r.created_at DESC, r.id DESC""")
                .param("ids", orderIds).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CancelTarget> cancelTarget(long orderId, CustomerId customer) {
        return jdbc.sql("""
                SELECT id, public_code, owner_id, contact_name, total, payment_method, paid_at IS NOT NULL,
                    mp_payment_id
                  FROM shop_orders WHERE id = :id AND customer_id = :customer""")
                .param("id", orderId).param("customer", customer.value())
                .query((rs, row) -> new CancelTarget(rs.getLong(1), rs.getInt(2), new OwnerId(rs.getLong(3)),
                        rs.getString(4), Jdbc.money(rs, "total"), rs.getString(6), rs.getBoolean(7), rs.getString(8)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public boolean lockPaid(long orderId) {
        return jdbc.sql("SELECT paid_at IS NOT NULL FROM shop_orders WHERE id = :id FOR UPDATE").param("id", orderId)
                .query(Boolean.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> lockStatuses(long orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE shop_order_id = :id FOR UPDATE").param("id", orderId)
                .query(String.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public void cancelByCustomer(long orderId, String label, String code, String note) {
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE shop_order_id = :id AND status = 'novo'")
                .param("id", orderId).update();
        jdbc.sql("""
                UPDATE shop_orders SET cancelled_at = now(), cancel_reason = :label, cancel_reason_code = :code,
                       cancelled_by = 'cliente', cancel_note = :note
                 WHERE id = :id""")
                .param("id", orderId).param("label", label).param("code", code).param("note", note).update();
    }

    /** {@inheritDoc} */
    @Override
    public void syncDelivered(long orderId) {
        jdbc.sql("""
                UPDATE shop_orders so SET delivered_at = CASE
                    WHEN EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status = 'entregue')
                     AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id
                                     AND o.status NOT IN ('entregue', 'cancelado'))
                    THEN COALESCE(so.delivered_at, now()) ELSE NULL END
                 WHERE so.id = :id""").param("id", orderId).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<StatusInfo> statusInfo(long orderId) {
        return jdbc.sql("""
                SELECT (SELECT array_agg(o.status) FROM orders o WHERE o.shop_order_id = so.id) AS statuses,
                       so.customer_id, so.public_code, so.total, so.fulfillment, so.time_window, so.paid_at IS NOT NULL
                  FROM shop_orders so WHERE so.id = :id""")
                .param("id", orderId)
                .query((rs, row) -> new StatusInfo(strings(rs.getArray(1)), new CustomerId(rs.getLong(2)), rs.getInt(3),
                        Money.fromDecimal(rs.getBigDecimal(4)), rs.getString(5), rs.getString(6), rs.getBoolean(7)))
                .optional();
    }
}
