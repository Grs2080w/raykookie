package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.application.ports.RecipeVariants;
import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Versões de receita no Postgres.
 */
@Repository
public class JdbcRecipeVariants implements RecipeVariants {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcRecipeVariants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> ofRecipe(long recipeId) {
        return jdbc.sql("""
                SELECT v.id, v.name, v.position, v.yield_units::float8 AS yield_units,
                       v.prep_hours::float8 AS prep_hours,
                       COALESCE((SELECT json_agg(json_build_object('ingredient_id', vi.ingredient_id,
                                                                   'qty', vi.qty::float8) ORDER BY vi.id)
                                   FROM recipe_variant_items vi WHERE vi.variant_id = v.id), '[]') AS items
                  FROM recipe_variants v WHERE v.recipe_id = :recipe ORDER BY v.position, v.id""")
                .param("recipe", recipeId).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> defaultOf(long recipeId) {
        return jdbc.sql("SELECT default_variant_id FROM recipes WHERE id = :recipe AND default_variant_id IS NOT NULL")
                .param("recipe", recipeId).query(Long.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public int count(long recipeId) {
        return jdbc.sql("SELECT count(*)::int FROM recipe_variants WHERE recipe_id = :recipe")
                .param("recipe", recipeId).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public boolean belongs(long recipeId, long variantId) {
        return jdbc.sql("SELECT 1 FROM recipe_variants WHERE id = :id AND recipe_id = :recipe")
                .param("id", variantId).param("recipe", recipeId).query(Integer.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public long create(long recipeId, String name, double yieldUnits, double prepHours) {
        return jdbc.sql("""
                INSERT INTO recipe_variants (recipe_id, name, position, yield_units, prep_hours)
                VALUES (:recipe, :name,
                        (SELECT COALESCE(MAX(position) + 1, 0) FROM recipe_variants WHERE recipe_id = :recipe),
                        :yield, :prep) RETURNING id""")
                .param("recipe", recipeId).param("name", name).param("yield", JdbcIngredients.dec(yieldUnits))
                .param("prep", JdbcIngredients.dec(prepHours)).query(Long.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public void update(long variantId, String name, double yieldUnits, double prepHours) {
        jdbc.sql("UPDATE recipe_variants SET name = :name, yield_units = :yield, prep_hours = :prep WHERE id = :id")
                .param("id", variantId).param("name", name).param("yield", JdbcIngredients.dec(yieldUnits))
                .param("prep", JdbcIngredients.dec(prepHours)).update();
    }

    /** {@inheritDoc} */
    @Override
    public void replaceItems(long variantId, List<RecipeForm.Item> items) {
        jdbc.sql("DELETE FROM recipe_variant_items WHERE variant_id = :variant").param("variant", variantId).update();
        for (RecipeForm.Item item : items) {
            jdbc.sql("""
                    INSERT INTO recipe_variant_items (variant_id, ingredient_id, qty)
                    VALUES (:variant, :ingredient, :qty)""")
                    .param("variant", variantId).param("ingredient", item.ingredientId())
                    .param("qty", JdbcIngredients.dec(item.qty()))
                    .update();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void copyItems(long from, long to) {
        jdbc.sql("""
                INSERT INTO recipe_variant_items (variant_id, ingredient_id, qty)
                SELECT :to, ingredient_id, qty FROM recipe_variant_items WHERE variant_id = :from ORDER BY id""")
                .param("from", from).param("to", to).update();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long variantId) {
        jdbc.sql("DELETE FROM recipe_variants WHERE id = :id").param("id", variantId).update();
    }

    /** {@inheritDoc} */
    @Override
    public void makeDefault(long recipeId, long variantId) {

        // ── 1. Padrão, rendimento e preparo no produto.
        jdbc.sql("""
                UPDATE recipes r SET default_variant_id = v.id, yield_units = v.yield_units, prep_hours = v.prep_hours
                  FROM recipe_variants v WHERE v.id = :variant AND r.id = :recipe AND v.recipe_id = r.id""")
                .param("variant", variantId).param("recipe", recipeId).update();

        // ── 2. Os ingredientes da padrão viram os do produto (recipe_items).
        jdbc.sql("DELETE FROM recipe_items WHERE recipe_id = :recipe").param("recipe", recipeId).update();
        jdbc.sql("""
                INSERT INTO recipe_items (recipe_id, ingredient_id, qty)
                SELECT :recipe, ingredient_id, qty FROM recipe_variant_items WHERE variant_id = :variant ORDER BY id""")
                .param("recipe", recipeId).param("variant", variantId).update();
    }
}
