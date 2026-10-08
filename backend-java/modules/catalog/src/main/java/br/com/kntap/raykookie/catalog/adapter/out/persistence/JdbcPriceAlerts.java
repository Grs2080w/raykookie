package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import static br.com.kntap.raykookie.catalog.adapter.out.persistence.JdbcIngredients.dec;

import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.catalog.application.ports.PriceAlerts;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Alertas de preço com o mesmo SQL de {@code /api/price-alerts} e de {@code backend/lib/price-impact.ts}.
 */
@Repository
public class JdbcPriceAlerts implements PriceAlerts {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPriceAlerts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> unread(OwnerId owner) {
        return jdbc.sql("""
                SELECT a.id, a.recipe_id, r.name AS recipe, a.kind, a.before_value::float8 AS before_value,
                       a.after_value::float8 AS after_value, a.suggested::float8 AS suggested, a.cause, a.created_at
                  FROM price_alerts a JOIN recipes r ON r.id = a.recipe_id
                 WHERE a.user_id = :owner AND NOT a.read ORDER BY a.created_at DESC LIMIT 50""")
                .param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public void create(OwnerId owner, long recipeId, long ingredientId, PricingEngine.MarginAlert alert,
            String cause) {
        jdbc.sql("""
                INSERT INTO price_alerts (user_id, recipe_id, ingredient_id, kind, before_value, after_value,
                                          suggested, cause)
                VALUES (:owner, :recipe, :ingredient, :kind, :before, :after, :suggested, :cause)""")
                .param("owner", owner.value()).param("recipe", recipeId).param("ingredient", ingredientId)
                .param("kind", alert.kind()).param("before", dec(alert.before())).param("after", dec(alert.after()))
                .param("suggested", alert.suggested() == null ? null : dec(alert.suggested()))
                .param("cause", cause).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Suggestion> suggestion(OwnerId owner, long id) {
        return jdbc.sql("SELECT recipe_id, suggested FROM price_alerts WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> {
                    BigDecimal suggested = rs.getBigDecimal(2);
                    return new Suggestion(rs.getLong(1), suggested == null ? null : suggested.doubleValue());
                })
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void markRead(OwnerId owner, long id) {
        jdbc.sql("UPDATE price_alerts SET read = true WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }
}
