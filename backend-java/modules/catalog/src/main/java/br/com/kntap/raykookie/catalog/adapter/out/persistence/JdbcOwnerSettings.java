package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import static br.com.kntap.raykookie.catalog.adapter.out.persistence.JdbcIngredients.dec;

import br.com.kntap.raykookie.catalog.application.ports.OwnerSettings;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ajustes com o mesmo SQL de {@code /api/settings}.
 */
@Repository
public class JdbcOwnerSettings implements OwnerSettings {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcOwnerSettings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Values> find(OwnerId owner) {
        return jdbc.sql("SELECT hourly_rate, monthly_units, min_margin_pct FROM settings WHERE user_id = :owner")
                .param("owner", owner.value())
                .query((rs, row) -> new Values(rs.getBigDecimal(1).doubleValue(), rs.getBigDecimal(2).doubleValue(),
                        rs.getBigDecimal(3).doubleValue()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> save(OwnerId owner, SimpleForms.Settings form) {
        return jdbc.sql("""
                INSERT INTO settings (user_id, hourly_rate, monthly_units, min_margin_pct)
                VALUES (:owner, :rate, :units, :initialMin)
                ON CONFLICT (user_id) DO UPDATE SET hourly_rate = EXCLUDED.hourly_rate,
                       monthly_units = EXCLUDED.monthly_units,
                       min_margin_pct = COALESCE(CAST(:min AS numeric), settings.min_margin_pct)
                RETURNING *""")
                .param("owner", owner.value()).param("rate", dec(form.hourlyRate()))
                .param("units", dec(form.monthlyUnits())).param("initialMin", dec(form.initialMinMargin()))
                .param("min", form.minMargin() == null ? null : dec(form.minMargin()))
                .query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public void createDefaults(OwnerId owner) {
        jdbc.sql("INSERT INTO settings (user_id) VALUES (:owner) ON CONFLICT DO NOTHING")
                .param("owner", owner.value()).update();
    }
}
