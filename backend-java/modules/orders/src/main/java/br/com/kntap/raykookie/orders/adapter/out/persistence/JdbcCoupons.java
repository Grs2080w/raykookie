package br.com.kntap.raykookie.orders.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.Coupons;
import br.com.kntap.raykookie.orders.domain.CouponRules;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cupons no Postgres.
 */
@Repository
public class JdbcCoupons implements Coupons {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcCoupons(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CouponRules.Coupon> find(OwnerId owner, String code) {
        return jdbc.sql("""
                SELECT id, code, percent, min_total, min_inclusive, active FROM coupons
                 WHERE user_id = :owner AND upper(code) = upper(:code)""")
                .param("owner", owner.value()).param("code", code)
                .query((rs, row) -> new CouponRules.Coupon(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4) == null ? null : Money.fromDecimal(rs.getBigDecimal(4)), rs.getBoolean(5),
                        rs.getBoolean(6)))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql("""
                SELECT c.id, c.code, c.percent::float8 AS percent, c.min_total::float8 AS min_total, c.min_inclusive,
                       c.active, c.created_at,
                       (SELECT count(*)::int FROM shop_orders so WHERE so.coupon_id = c.id AND so.cancelled_at IS NULL)
                           AS uses
                  FROM coupons c WHERE c.user_id = :owner ORDER BY c.created_at DESC, c.id DESC""")
                .param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public boolean codeTaken(OwnerId owner, String code) {
        return find(owner, code).isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public long create(OwnerId owner, Form form) {
        return jdbc.sql("""
                INSERT INTO coupons (user_id, code, percent, min_total, min_inclusive, active)
                VALUES (:owner, :code, :percent, :min, :inclusive, :active) RETURNING id""")
                .param("owner", owner.value()).param("code", form.code()).param("percent", form.percent())
                .param("min", form.minTotal()).param("inclusive", form.minInclusive()).param("active", form.active())
                .query(Long.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public boolean update(OwnerId owner, long id, Form form) {
        return jdbc.sql("""
                UPDATE coupons SET percent = :percent, min_total = :min, min_inclusive = :inclusive, active = :active
                 WHERE id = :id AND user_id = :owner""")
                .param("id", id).param("owner", owner.value()).param("percent", form.percent())
                .param("min", form.minTotal()).param("inclusive", form.minInclusive()).param("active", form.active())
                .update() > 0;
    }

    /** {@inheritDoc} */
    @Override
    public int uses(OwnerId owner, long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM shop_orders so JOIN coupons c ON c.id = so.coupon_id
                 WHERE c.id = :id AND c.user_id = :owner""")
                .param("id", id).param("owner", owner.value()).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM coupons WHERE id = :id AND user_id = :owner").param("id", id)
                .param("owner", owner.value()).update();
    }
}
