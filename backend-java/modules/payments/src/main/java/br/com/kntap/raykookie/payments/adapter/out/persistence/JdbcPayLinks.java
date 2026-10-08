package br.com.kntap.raykookie.payments.adapter.out.persistence;

import static br.com.kntap.raykookie.payments.adapter.out.persistence.JdbcOrderCharges.strings;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.payments.application.ports.PayLinks;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Links de pagamento com o SQL de {@code backend/lib/pay-link.ts}.
 */
@Repository
public class JdbcPayLinks implements PayLinks {

    /** JSON dos itens. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPayLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LinkOrder> order(long orderId, OwnerId owner) {
        return jdbc.sql("""
                SELECT status, payment, shop_order_id, qty * unit_price AS total FROM orders
                 WHERE id = :id AND user_id = :owner""")
                .param("id", orderId).param("owner", owner.value())
                .query((rs, row) -> new LinkOrder(rs.getString(1), rs.getString(2),
                        rs.getObject(3) == null ? null : rs.getLong(3), rs.getBigDecimal(4)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Previous> previous(long orderId) {
        return jdbc.sql("SELECT amount, mp_payment_id, paid_at IS NOT NULL FROM order_pay_links WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, row) -> new Previous(Jdbc.money(rs, "amount"), rs.getString(2), rs.getBoolean(3)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> upsert(long orderId, String token, Money amount) {
        return jdbc.sql("""
                INSERT INTO order_pay_links (order_id, token, amount) VALUES (:order, :token, :amount)
                ON CONFLICT (order_id) DO UPDATE SET
                  amount = EXCLUDED.amount,
                  pix_qr_code = CASE WHEN order_pay_links.amount = EXCLUDED.amount
                                     THEN order_pay_links.pix_qr_code ELSE '' END,
                  pix_qr_base64 = CASE WHEN order_pay_links.amount = EXCLUDED.amount
                                       THEN order_pay_links.pix_qr_base64 ELSE '' END,
                  pix_expires_at = CASE WHEN order_pay_links.amount = EXCLUDED.amount
                                        THEN order_pay_links.pix_expires_at ELSE NULL END
                WHERE order_pay_links.paid_at IS NULL
                RETURNING token""")
                .param("order", orderId).param("token", token).param("amount", amount.toDecimal())
                .query(String.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Link> byToken(String token) {
        return jdbc.sql("""
                SELECT l.id, l.amount, l.paid_at, l.mp_payment_id, l.pix_qr_code, l.pix_qr_base64, l.pix_expires_at,
                       l.pix_attempts, l.card_attempts, o.number, o.customer, o.product, o.qty, o.unit_price, o.status,
                       o.payment, o.delivery_date::text AS delivery_date
                  FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.token = :token""")
                .param("token", token)
                .query((rs, row) -> new Link(rs.getLong("id"), Jdbc.money(rs, "amount"), Jdbc.instant(rs, "paid_at"),
                        rs.getString("mp_payment_id"), rs.getString("pix_qr_code"), rs.getString("pix_qr_base64"),
                        Jdbc.instant(rs, "pix_expires_at"), rs.getInt("pix_attempts"), rs.getInt("card_attempts"),
                        rs.getInt("number"), rs.getString("customer"), rs.getString("product"),
                        rs.getBigDecimal("qty"), rs.getBigDecimal("unit_price"), rs.getString("status"),
                        rs.getString("payment"), rs.getString("delivery_date")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Guest> guest(String token) {
        return jdbc.sql("""
                SELECT l.id, l.paid_at IS NOT NULL, l.shop_order_id, so.customer_id
                  FROM order_pay_links l LEFT JOIN shop_orders so ON so.id = l.shop_order_id WHERE l.token = :token""")
                .param("token", token)
                .query((rs, row) -> new Guest(rs.getLong(1), rs.getBoolean(2),
                        rs.getObject(3) == null ? null : rs.getLong(3), rs.getObject(4) == null ? null : rs.getLong(4)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void savePix(long linkId, String paymentId, String code, String qrBase64, Instant expiresAt,
            int attempt) {
        jdbc.sql("""
                UPDATE order_pay_links SET mp_payment_id = :payment, pix_qr_code = :code, pix_qr_base64 = :qr,
                       pix_expires_at = :expires, pix_attempts = :attempt,
                       mp_payment_ids = array_append(mp_payment_ids, :payment)
                 WHERE id = :id""")
                .param("id", linkId).param("payment", paymentId).param("code", code).param("qr", qrBase64)
                .param("expires", Jdbc.timestamp(expiresAt)).param("attempt", attempt).update();
    }

    /** {@inheritDoc} */
    @Override
    public boolean paid(long linkId) {
        return jdbc.sql("""
                SELECT l.paid_at IS NOT NULL OR o.payment = 'pago'
                  FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = :id""")
                .param("id", linkId).query(Boolean.class).optional().orElse(false);
    }

    /** {@inheritDoc} */
    @Override
    public void addCardPayment(long linkId, String paymentId, Integer installments) {
        jdbc.sql("""
                UPDATE order_pay_links SET mp_payment_id = :payment,
                       mp_payment_ids = CASE WHEN :payment = ANY(mp_payment_ids) THEN mp_payment_ids
                                             ELSE array_append(mp_payment_ids, :payment) END,
                       installments = COALESCE(CAST(:installments AS int), installments)
                 WHERE id = :id""")
                .param("id", linkId).param("payment", paymentId).param("installments", installments).update();
    }

    /** {@inheritDoc} */
    @Override
    public void countCardAttempt(long linkId, String paymentId) {
        jdbc.sql("""
                UPDATE order_pay_links SET card_attempts = card_attempts + 1,
                       mp_payment_ids = CASE WHEN CAST(:payment AS text) IS NULL THEN mp_payment_ids
                                             ELSE array_append(mp_payment_ids, :payment) END
                 WHERE id = :id""").param("id", linkId).param("payment", paymentId).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LineState> lockLine(long linkId) {
        return jdbc.sql("""
                SELECT o.id, o.status, o.payment FROM order_pay_links l JOIN orders o ON o.id = l.order_id
                 WHERE l.id = :id FOR UPDATE OF o""")
                .param("id", linkId)
                .query((rs, row) -> new LineState(rs.getLong(1), rs.getString(2), rs.getString(3))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> markPaid(long linkId, String paymentId, Money paidAmount) {
        return jdbc.sql("""
                UPDATE order_pay_links SET paid_at = now(), paid_mp_id = :payment
                 WHERE id = :id AND :payment = ANY(mp_payment_ids) AND paid_at IS NULL AND :amount >= amount
                RETURNING order_id""")
                .param("id", linkId).param("payment", paymentId).param("amount", paidAmount.toDecimal())
                .query(Long.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromotionSource> lockForPromotion(long linkId) {
        return jdbc.sql("""
                SELECT l.amount, l.mp_payment_ids, l.shop_order_id, o.id AS order_id, o.user_id, o.customer, o.product,
                       o.qty, o.status, o.payment, o.order_date, o.delivery_date
                  FROM order_pay_links l JOIN orders o ON o.id = l.order_id WHERE l.id = :id FOR UPDATE OF l""")
                .param("id", linkId)
                .query((rs, row) -> rs.getObject("shop_order_id") != null ? Optional.<PromotionSource>empty()
                        : Optional.of(new PromotionSource(
                        Jdbc.money(rs, "amount"), strings(rs.getArray("mp_payment_ids")), rs.getLong("order_id"),
                        new OwnerId(rs.getLong("user_id")), rs.getString("customer"), rs.getString("product"),
                        rs.getBigDecimal("qty"), rs.getString("status"), rs.getString("payment"),
                        rs.getObject("order_date", LocalDate.class), rs.getObject("delivery_date", LocalDate.class))))
                .optional().flatMap(found -> found);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> lockLegacyPaid(long linkId) {
        return jdbc.sql("""
                SELECT paid_mp_id FROM order_pay_links
                 WHERE id = :id AND paid_at IS NOT NULL AND shop_order_id IS NULL AND paid_mp_id IS NOT NULL
                 FOR UPDATE""")
                .param("id", linkId).query(String.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public long promote(Promotion p) {

        // ── 1. Cliente convidado (não entra por e-mail nem Google: o e-mail é técnico).
        PromotionSource l = p.source();
        long guest = jdbc.sql("INSERT INTO customers (email, name, guest) VALUES (:email, :name, true) RETURNING id")
                .param("email", "link-" + p.linkId() + "@convidado.invalid").param("name", l.customer())
                .query(Long.class).single();

        // ── 2. Pedido do app já pago (e já cancelado, se a linha estava).
        long shopOrderId = jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                       time_window, payment_method, items, total, public_code, paid_at, paid_mp_id, mp_payment_ids,
                       manual, cancelled_at, cancelled_by, cancel_reason_code, cancel_reason)
                VALUES (:customer, :owner, :name, '', 'retirada', COALESCE(:delivery, :ordered), 'tarde', 'pix',
                        CAST(:items AS jsonb), :total, :code, now(), :paid, CAST(:ids AS text[]), true,
                        CASE WHEN :cancelled THEN now() END, CASE WHEN :cancelled THEN 'loja' END,
                        CASE WHEN :cancelled THEN 'outro' END,
                        CASE WHEN :cancelled THEN 'Pedido cancelado pela loja' ELSE '' END)
                RETURNING id""")
                .param("customer", guest).param("owner", l.owner().value()).param("name", l.customer())
                .param("delivery", l.deliveryDate()).param("ordered", l.orderDate()).param("items", items(p))
                .param("total", l.amount().toDecimal()).param("code", p.code()).param("paid", p.paidMpId())
                .param("ids", arrayLiteral(l.paymentIds())).param("cancelled", p.cancelled())
                .query(Long.class).single();

        // ── 3. Liga a linha e o link ao pedido do app.
        jdbc.sql("""
                UPDATE orders SET shop_order_id = :so,
                       payment = CASE WHEN status = 'cancelado' THEN payment ELSE 'pago' END
                 WHERE id = :id""").param("so", shopOrderId).param("id", l.orderId()).update();
        jdbc.sql("UPDATE order_pay_links SET shop_order_id = :so WHERE id = :id").param("so", shopOrderId)
                .param("id", p.linkId()).update();
        return shopOrderId;
    }

    /**
     * Item único do pedido promovido ({@code [{id, name, qty: 1, unit_price}]}).
     *
     * @param p promoção
     * @return o JSON
     */
    private static String items(Promotion p) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "manual-" + p.source().orderId());
        item.put("name", p.itemName());
        item.put("qty", 1);
        item.put("unit_price", NodeRows.number(p.source().amount().toDecimal()));
        return JSON.writeValueAsString(List.of(item));
    }

    /**
     * Lista como literal de array do Postgres ({@code {"a","b"}}); o JDBC expandiria um array Java como lista do IN.
     *
     * @param values valores
     * @return o literal
     */
    static String arrayLiteral(List<String> values) {
        return values.stream().map(v -> '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /** {@inheritDoc} */
    @Override
    public void deliveredAtLegacyPayment(long shopOrderId) {
        jdbc.sql("""
                UPDATE shop_orders SET delivered_at = (SELECT paid_at FROM order_pay_links WHERE shop_order_id = :id)
                 WHERE id = :id AND delivered_at IS NULL""").param("id", shopOrderId).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<OpenLink> open() {
        return jdbc.sql("""
                SELECT id, mp_payment_ids FROM order_pay_links
                 WHERE paid_at IS NULL AND cardinality(mp_payment_ids) > 0 AND created_at > now() - interval '2 days'
                 ORDER BY id""")
                .query((rs, row) -> new OpenLink(rs.getLong(1), strings(rs.getArray(2)))).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> legacy() {
        return jdbc
                .sql("SELECT id FROM order_pay_links WHERE paid_at IS NOT NULL AND shop_order_id IS NULL ORDER BY id")
                .query(Long.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> orphans() {
        return jdbc.sql("""
                SELECT so.id FROM shop_orders so
                  JOIN order_pay_links l ON l.shop_order_id = so.id AND l.paid_at = so.paid_at
                 WHERE so.manual AND so.paid_at IS NOT NULL AND so.cancelled_at IS NOT NULL AND so.cancelled_by = 'loja'
                   AND so.refunded_total = 0 AND NOT EXISTS (SELECT 1 FROM refunds r WHERE r.shop_order_id = so.id)
                 ORDER BY so.id""")
                .query(Long.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Promoted> promoted(long linkId) {
        return jdbc.sql("SELECT shop_order_id, paid_mp_id FROM order_pay_links WHERE id = :id").param("id", linkId)
                .query((rs, row) -> new Promoted(rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2)))
                .optional();
    }
}
