package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ingredientes com o mesmo SQL das rotas {@code /api/ingredients} e de {@code backend/lib/price-impact.ts}.
 */
@Repository
public class JdbcIngredients implements Ingredients {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcIngredients(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql("SELECT * FROM ingredients WHERE user_id = :owner ORDER BY lower(name)")
                .param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, IngredientForm form) {
        return params(jdbc.sql("""
                INSERT INTO ingredients (user_id, category_id, name, unit, stock_qty, package_size, package_cost,
                                         min_stock, supplier, notes)
                VALUES (:owner, :category, :name, :unit, :stock, :size, :cost, :min, :supplier, :notes)
                RETURNING *"""), form).param("owner", owner.value()).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(OwnerId owner, long id, IngredientForm form) {
        return params(jdbc.sql("""
                UPDATE ingredients SET category_id = :category, name = :name, unit = :unit, stock_qty = :stock,
                       package_size = :size, package_cost = :cost, min_stock = :min, supplier = :supplier,
                       notes = :notes
                 WHERE id = :id AND user_id = :owner RETURNING *"""), form)
                .param("id", id).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /**
     * Preenche os campos do formulário.
     *
     * @param spec comando
     * @param form dados
     * @return o comando com os parâmetros
     */
    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, IngredientForm form) {
        return spec.param("category", form.categoryId()).param("name", form.name()).param("unit", form.unit())
                .param("stock", dec(form.stockQty())).param("size", dec(form.packageSize()))
                .param("cost", dec(form.packageCost())).param("min", dec(form.minStock()))
                .param("supplier", form.supplier()).param("notes", form.notes());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> updateStock(OwnerId owner, long id, double stockQty) {
        return jdbc.sql("UPDATE ingredients SET stock_qty = :stock WHERE id = :id AND user_id = :owner RETURNING *")
                .param("stock", dec(stockQty)).param("id", id).param("owner", owner.value())
                .query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PriceState> price(OwnerId owner, long id) {
        return jdbc.sql("SELECT package_cost, package_size FROM ingredients WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> new PriceState(rs.getBigDecimal(1).doubleValue(),
                        rs.getBigDecimal(2).doubleValue()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<NameAndUnit> nameAndUnit(OwnerId owner, long id) {
        return jdbc.sql("SELECT name, unit FROM ingredients WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> new NameAndUnit(rs.getString(1), rs.getString(2))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void markPriceUpdated(long id) {
        jdbc.sql("UPDATE ingredients SET price_updated_at = now() WHERE id = :id").param("id", id).update();
    }

    /** {@inheritDoc} */
    @Override
    public int usedInRecipes(OwnerId owner, long id) {
        return jdbc.sql("""
                SELECT count(DISTINCT r.id)::int FROM recipes r
                 WHERE r.user_id = :owner
                   AND (EXISTS (SELECT 1 FROM recipe_items ri WHERE ri.recipe_id = r.id AND ri.ingredient_id = :id)
                        OR EXISTS (SELECT 1 FROM recipe_variants v JOIN recipe_variant_items vi ON vi.variant_id = v.id
                                    WHERE v.recipe_id = r.id AND vi.ingredient_id = :id))""")
                .param("id", id).param("owner", owner.value()).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM ingredients WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public int countOwned(OwnerId owner, Collection<Long> ids) {
        return jdbc.sql("SELECT count(*)::int FROM ingredients WHERE user_id = :owner AND id IN (:ids)")
                .param("owner", owner.value()).param("ids", ids).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, Double> unitCosts(OwnerId owner) {
        Map<Long, Double> costs = new HashMap<>();
        jdbc.sql("SELECT id, package_cost, package_size FROM ingredients WHERE user_id = :owner")
                .param("owner", owner.value())
                .query(rs -> {
                    costs.put(rs.getLong(1), PricingEngine.unitCost(rs.getBigDecimal(2).doubleValue(),
                            rs.getBigDecimal(3).doubleValue()));
                });
        return costs;
    }

    /** {@inheritDoc} */
    @Override
    public void recordQuote(OwnerId owner, long ingredientId, Long supplierId, PriceState price, String unit,
            String source) {
        jdbc.sql("""
                INSERT INTO price_quotes (user_id, ingredient_id, supplier_id, price, pack_size, pack_unit, source)
                VALUES (:owner, :ingredient, :supplier, :price, :size, :unit, :source)""")
                .param("owner", owner.value()).param("ingredient", ingredientId).param("supplier", supplierId)
                .param("price", dec(price.cost())).param("size", dec(price.size())).param("unit", unit)
                .param("source", source).update();
    }

    /**
     * Número como o Node o envia ao Postgres ({@code String(n)}); o banco arredonda para as casas da coluna.
     *
     * @param value número
     * @return o decimal
     */
    static BigDecimal dec(double value) {
        return BigDecimal.valueOf(value);
    }
}
