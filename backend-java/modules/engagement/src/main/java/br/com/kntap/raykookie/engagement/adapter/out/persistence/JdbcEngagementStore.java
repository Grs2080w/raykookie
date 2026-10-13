package br.com.kntap.raykookie.engagement.adapter.out.persistence;

import br.com.kntap.raykookie.engagement.application.ports.EngagementStore;
import br.com.kntap.raykookie.engagement.domain.EngagementBatch;
import br.com.kntap.raykookie.engagement.domain.EngagementEvent;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eventos e agregado diário no Postgres.
 */
@Repository
public class JdbcEngagementStore implements EngagementStore {

    /** Filtro de plataforma opcional (nulo = todas). */
    private static final String PLATFORM = "(CAST(:platform AS text) IS NULL OR platform = CAST(:platform AS text))";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcEngagementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public int insert(EngagementBatch batch) {
        int inserted = 0;
        for (EngagementEvent event : batch.events()) {
            inserted += jdbc.sql("""
                    INSERT INTO engagement_events (occurred_at, session_id, platform, app_version, name, route,
                                                   item_id, detail, qty)
                    VALUES (:at, :session, :platform, :version, :name, :route, :item, :detail, :qty)
                    ON CONFLICT DO NOTHING""")
                    .param("at", Timestamp.from(event.at())).param("session", batch.sessionId())
                    .param("platform", batch.platform()).param("version", batch.appVersion())
                    .param("name", event.name()).param("route", event.route()).param("item", event.itemId())
                    .param("detail", event.detail()).param("qty", event.qty()).update();
        }
        return inserted;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void rollup(LocalDate day) {
        jdbc.sql("DELETE FROM engagement_daily WHERE day = :day").param("day", day).update();
        jdbc.sql("""
                WITH e AS (SELECT platform, name, route, item_id, detail, session_id FROM engagement_events
                            WHERE occurred_at >= :from AND occurred_at < :to)
                INSERT INTO engagement_daily (day, platform, name, route, item_id, detail, events, sessions)
                SELECT :day, platform, name, '*', '*', '*', count(*), count(DISTINCT session_id)
                  FROM e GROUP BY platform, name
                UNION ALL
                SELECT :day, platform, name, '*', '*', detail, count(*), count(DISTINCT session_id)
                  FROM e WHERE detail <> '' GROUP BY platform, name, detail
                UNION ALL
                SELECT :day, platform, name, route, '*', '*', count(*), count(DISTINCT session_id)
                  FROM e WHERE route <> '' GROUP BY platform, name, route
                UNION ALL
                SELECT :day, platform, name, '*', item_id, '*', count(*), count(DISTINCT session_id)
                  FROM e WHERE item_id <> '' GROUP BY platform, name, item_id""")
                .param("day", day).param("from", Timestamp.from(start(day))).param("to", Timestamp.from(start(
                        day.plusDays(1)))).update();
    }

    /** {@inheritDoc} */
    @Override
    public int purgeEventsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM engagement_events WHERE occurred_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
    }

    /** {@inheritDoc} */
    @Override
    public int purgeDailyBefore(LocalDate cutoff) {
        return jdbc.sql("DELETE FROM engagement_daily WHERE day < :cutoff").param("cutoff", cutoff).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Step> steps(LocalDate from, LocalDate to, String platform) {
        return jdbc.sql("SELECT name, detail, sum(events)::bigint, sum(sessions)::bigint FROM engagement_daily "
                + "WHERE day BETWEEN :from AND :to AND route = '*' AND item_id = '*' AND " + PLATFORM
                + " GROUP BY name, detail ORDER BY name, detail")
                .param("from", from).param("to", to).param("platform", platform)
                .query((rs, row) -> new Step(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<PageRow> pages(LocalDate from, LocalDate to, String platform) {
        return jdbc.sql("SELECT route, sum(events)::bigint, sum(sessions)::bigint FROM engagement_daily "
                + "WHERE day BETWEEN :from AND :to AND name = 'screen_view' AND route <> '*' AND " + PLATFORM
                + " GROUP BY route ORDER BY sum(events) DESC, route")
                .param("from", from).param("to", to).param("platform", platform)
                .query((rs, row) -> new PageRow(rs.getString(1), rs.getLong(2), rs.getLong(3))).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<ItemRow> items(LocalDate from, LocalDate to, String platform) {
        return jdbc.sql("SELECT item_id, name, sum(events)::bigint, sum(sessions)::bigint FROM engagement_daily "
                + "WHERE day BETWEEN :from AND :to AND item_id <> '*' AND " + PLATFORM
                + " GROUP BY item_id, name")
                .param("from", from).param("to", to).param("platform", platform)
                .query((rs, row) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public List<PlatformRow> platforms(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT platform, sum(sessions)::bigint FROM engagement_daily "
                + "WHERE day BETWEEN :from AND :to AND name = 'screen_view' AND route = '*' AND item_id = '*' "
                + "AND detail = '*' GROUP BY platform ORDER BY platform")
                .param("from", from).param("to", to)
                .query((rs, row) -> new PlatformRow(rs.getString(1), rs.getLong(2))).list();
    }

    /** {@inheritDoc} */
    @Override
    public OrderTotals orders(OwnerId owner, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT count(*)::bigint, count(paid_at)::bigint,
                       COALESCE(sum(total) FILTER (WHERE paid_at IS NOT NULL), 0)
                  FROM shop_orders
                 WHERE owner_id = :owner AND cancelled_at IS NULL
                   AND (created_at AT TIME ZONE :zone)::date BETWEEN :from AND :to""")
                .param("owner", owner.value()).param("zone", StoreClock.ZONE.getId()).param("from", from)
                .param("to", to).query((rs, row) -> new OrderTotals(rs.getLong(1), rs.getLong(2),
                        rs.getBigDecimal(3))).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<SoldItem> soldItems(OwnerId owner, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT line->>'id', count(DISTINCT s.id)::bigint,
                       COALESCE(sum((line->>'qty')::numeric * COALESCE((line->>'unit_price')::numeric, 0)), 0)
                  FROM shop_orders s CROSS JOIN LATERAL jsonb_array_elements(s.items) AS line
                 WHERE s.owner_id = :owner AND s.cancelled_at IS NULL
                   AND (s.created_at AT TIME ZONE :zone)::date BETWEEN :from AND :to
                 GROUP BY 1""")
                .param("owner", owner.value()).param("zone", StoreClock.ZONE.getId()).param("from", from)
                .param("to", to).query((rs, row) -> new SoldItem(rs.getString(1), rs.getLong(2),
                        rs.getBigDecimal(3))).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> itemNames(OwnerId owner) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id::text, name FROM recipes WHERE user_id = :owner")
                .param("owner", owner.value()).query((rs, row) -> names.put(rs.getString(1), rs.getString(2))).list();
        jdbc.sql("SELECT 'combo-' || id, name FROM combos WHERE user_id = :owner")
                .param("owner", owner.value()).query((rs, row) -> names.put(rs.getString(1), rs.getString(2))).list();
        return names;
    }

    /**
     * Início de um dia no fuso da loja.
     *
     * @param day dia
     * @return o instante
     */
    private static Instant start(LocalDate day) {
        return day.atStartOfDay(StoreClock.ZONE).toInstant();
    }
}
