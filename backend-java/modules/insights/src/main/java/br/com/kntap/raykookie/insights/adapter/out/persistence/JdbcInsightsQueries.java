package br.com.kntap.raykookie.insights.adapter.out.persistence;

import br.com.kntap.raykookie.insights.application.ports.InsightsQueries;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Painel e metas, com o SQL de {@code getGoal}, {@code getMonthStats} e da rota {@code goals}.
 */
@Repository
public class JdbcInsightsQueries implements InsightsQueries {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcInsightsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> goal(OwnerId owner, LocalDate month) {
        return jdbc.sql("SELECT revenue, orders, new_customers, avg_ticket FROM goals"
                + " WHERE user_id = :u AND month = :m")
                .param("u", owner.value()).param("m", month).query(NodeRows.ROW).optional().orElseGet(() -> {
                    Map<String, Object> zero = new LinkedHashMap<>();
                    zero.put("revenue", 0L);
                    zero.put("orders", 0L);
                    zero.put("new_customers", 0L);
                    zero.put("avg_ticket", 0L);
                    return zero;
                });
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> saveGoal(OwnerId owner, LocalDate month, BigDecimal revenue, long orders,
            long newCustomers, BigDecimal avgTicket) {
        return jdbc.sql("""
                INSERT INTO goals (user_id, month, revenue, orders, new_customers, avg_ticket)
                VALUES (:u, :m, :revenue, :orders, :customers, :ticket)
                ON CONFLICT (user_id, month) DO UPDATE SET revenue = EXCLUDED.revenue, orders = EXCLUDED.orders,
                       new_customers = EXCLUDED.new_customers, avg_ticket = EXCLUDED.avg_ticket
                RETURNING *""")
                .param("u", owner.value()).param("m", month).param("revenue", revenue.max(BigDecimal.ZERO))
                .param("orders", Math.max(0, orders)).param("customers", Math.max(0, newCustomers))
                .param("ticket", avgTicket.max(BigDecimal.ZERO)).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public MonthNumbers month(OwnerId owner, LocalDate month) {
        return jdbc.sql("""
                WITH m AS (SELECT * FROM orders WHERE user_id = :u AND status <> 'cancelado' AND order_date >= :m
                                                  AND order_date < (CAST(:m AS date) + interval '1 month')),
                     first_seen AS (SELECT lower(customer) c, MIN(order_date) d FROM orders
                                     WHERE user_id = :u AND status <> 'cancelado' GROUP BY 1),
                     refunded AS (
                       SELECT GREATEST(0, so.refunded_total - COALESCE((SELECT SUM(o.qty * o.unit_price) FROM orders o
                                    WHERE o.shop_order_id = so.id AND o.status = 'cancelado'), 0)) AS v
                         FROM shop_orders so
                        WHERE so.refunded_total > 0
                          AND so.id IN (SELECT shop_order_id FROM m WHERE shop_order_id IS NOT NULL))
                SELECT COALESCE((SELECT SUM(qty * unit_price) FROM m), 0) AS revenue,
                       COALESCE((SELECT SUM(v) FROM refunded), 0) AS refunded,
                       (SELECT count(*) FROM m) AS orders,
                       (SELECT count(*) FROM first_seen
                         WHERE d >= :m AND d < (CAST(:m AS date) + interval '1 month')) AS new_customers""")
                .param("u", owner.value()).param("m", month)
                .query((rs, row) -> new MonthNumbers(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getLong(3),
                        rs.getLong(4)))
                .single();
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, BigDecimal> unitsSold(OwnerId owner, LocalDate month) {
        Map<Long, BigDecimal> units = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT recipe_id, SUM(qty) FROM orders
                 WHERE user_id = :u AND status <> 'cancelado' AND recipe_id IS NOT NULL AND order_date >= :m
                   AND order_date < (CAST(:m AS date) + interval '1 month')
                 GROUP BY recipe_id""")
                .param("u", owner.value()).param("m", month)
                .query((rs, row) -> units.put(rs.getLong(1), rs.getBigDecimal(2))).list();
        return units;
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, ComboSales> combosSold(OwnerId owner, LocalDate month) {
        Map<Long, ComboSales> sales = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT combo_id, SUM(qty), SUM(qty * unit_price) FROM orders
                 WHERE user_id = :u AND status <> 'cancelado' AND combo_id IS NOT NULL AND order_date >= :m
                   AND order_date < (CAST(:m AS date) + interval '1 month')
                 GROUP BY combo_id""")
                .param("u", owner.value()).param("m", month)
                .query((rs, row) -> sales.put(rs.getLong(1), new ComboSales(rs.getBigDecimal(2), rs.getBigDecimal(3))))
                .list();
        return sales;
    }

    /** {@inheritDoc} */
    @Override
    public RefundSummary refunds(OwnerId owner) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'solicitado'),
                       COALESCE(sum(approved_amount) FILTER (WHERE status IN ('aprovado', 'processando', 'falhou')), 0),
                       count(*) FILTER (WHERE status = 'falhou')
                  FROM refunds WHERE owner_id = :u""")
                .param("u", owner.value())
                .query((rs, row) -> new RefundSummary(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3))).single();
    }
}
