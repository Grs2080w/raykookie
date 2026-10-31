package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.application.ports.ProductionStore;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Estoque de produção e histórico de produção no Postgres ({@code recipe_stock} e {@code production_runs}).
 */
@Repository
public class JdbcProductionStore implements ProductionStore {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcProductionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, BigDecimal> recipeStock(OwnerId owner) {
        return pairs(jdbc.sql("SELECT recipe_id, qty FROM recipe_stock WHERE user_id = :owner")
                .param("owner", owner.value()));
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, BigDecimal> lockIngredientStock(OwnerId owner, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return pairs(jdbc.sql("""
                SELECT id, stock_qty FROM ingredients WHERE user_id = :owner AND id IN (:ids) ORDER BY id
                FOR UPDATE""").param("owner", owner.value()).param("ids", ids));
    }

    /**
     * Lê pares (id, quantidade) na ordem da consulta.
     *
     * @param spec consulta com duas colunas
     * @return o mapa, na ordem das linhas
     */
    private static Map<Long, BigDecimal> pairs(JdbcClient.StatementSpec spec) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        spec.query((rs, row) -> out.put(rs.getLong(1), rs.getBigDecimal(2))).list();
        return out;
    }

    /** {@inheritDoc} */
    @Override
    public void setIngredientStock(OwnerId owner, long id, BigDecimal qty) {
        jdbc.sql("UPDATE ingredients SET stock_qty = :qty WHERE id = :id AND user_id = :owner")
                .param("qty", qty).param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public BigDecimal lockRecipeStock(OwnerId owner, long recipeId) {
        jdbc.sql("""
                INSERT INTO recipe_stock (recipe_id, user_id, qty)
                SELECT id, user_id, 0 FROM recipes WHERE id = :recipe AND user_id = :owner
                ON CONFLICT (recipe_id) DO NOTHING""").param("recipe", recipeId).param("owner", owner.value())
                .update();
        return jdbc.sql("SELECT qty FROM recipe_stock WHERE recipe_id = :recipe AND user_id = :owner FOR UPDATE")
                .param("recipe", recipeId).param("owner", owner.value()).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    /** {@inheritDoc} */
    @Override
    public void setRecipeStock(OwnerId owner, long recipeId, BigDecimal qty) {
        jdbc.sql("""
                INSERT INTO recipe_stock (recipe_id, user_id, qty) VALUES (:recipe, :owner, :qty)
                ON CONFLICT (recipe_id) DO UPDATE SET qty = EXCLUDED.qty, updated_at = now()""")
                .param("recipe", recipeId).param("owner", owner.value()).param("qty", qty).update();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> insertRun(OwnerId owner, long recipeId, String recipe, BigDecimal qty,
            BigDecimal cost, String consumed, boolean forced) {
        return jdbc.sql("""
                INSERT INTO production_runs (user_id, recipe_id, recipe, qty, total_cost, consumed, forced)
                VALUES (:owner, :recipeId, :recipe, :qty, :cost, CAST(:consumed AS jsonb), :forced) RETURNING *""")
                .param("owner", owner.value()).param("recipeId", recipeId).param("recipe", recipe).param("qty", qty)
                .param("cost", cost).param("consumed", consumed).param("forced", forced)
                .query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> runs(OwnerId owner, Long recipeId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT * FROM production_runs
                 WHERE user_id = :owner AND (CAST(:recipe AS int) IS NULL OR recipe_id = :recipe)
                   AND (CAST(:from AS date) IS NULL OR (created_at AT TIME ZONE :zone)::date >= :from)
                   AND (CAST(:to AS date) IS NULL OR (created_at AT TIME ZONE :zone)::date <= :to)
                 ORDER BY created_at DESC, id DESC LIMIT 500""")
                .param("owner", owner.value()).param("recipe", recipeId).param("from", from).param("to", to)
                .param("zone", StoreClock.ZONE.getId()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> lockRun(OwnerId owner, long id) {
        return jdbc.sql("SELECT * FROM production_runs WHERE id = :id AND user_id = :owner FOR UPDATE")
                .param("id", id).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> markUndone(long id) {
        return jdbc.sql("UPDATE production_runs SET undone_at = now() WHERE id = :id RETURNING *")
                .param("id", id).query(NodeRows.ROW).single();
    }
}
