package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import static br.com.kntap.raykookie.catalog.adapter.out.persistence.JdbcIngredients.dec;

import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Receitas com o mesmo SQL de {@code backend/lib/queries.ts} ({@code getRecipes}) e de
 * {@code backend/lib/recipe-save.ts}.
 */
@Repository
public class JdbcRecipes implements Recipes {

    /** Receitas com itens e versão da foto. */
    private static final String LIST = """
            SELECT r.id, r.name, r.category_id, r.yield_units, r.margin_pct, r.prep_hours, r.notes,
                   r.unit_weight_g::float8 AS unit_weight_g, r.sale_price::float8 AS sale_price,
                   r.show_on_menu, r.tags, r.show_ingredients, r.default_variant_id,
                   (SELECT (extract(epoch from p.updated_at) * 1000)::bigint::text
                      FROM recipe_photos p WHERE p.recipe_id = r.id) AS photo_v,
                   COALESCE((SELECT json_agg(json_build_object('ingredient_id', ri.ingredient_id,
                                                               'qty', ri.qty::float8) ORDER BY ri.id)
                               FROM recipe_items ri WHERE ri.recipe_id = r.id), '[]') AS items
              FROM recipes r WHERE r.user_id = :owner ORDER BY lower(r.name)""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcRecipes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql(LIST).param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, RecipeForm form) {
        return params(jdbc.sql("""
                INSERT INTO recipes (user_id, category_id, name, yield_units, margin_pct, prep_hours, notes,
                                     unit_weight_g, sale_price)
                VALUES (:owner, :category, :name, :yield, :margin, :prep, :notes, :weight, :sale) RETURNING *"""),
                form).param("owner", owner.value()).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(OwnerId owner, long id, RecipeForm form) {
        return params(jdbc.sql("""
                UPDATE recipes SET category_id = :category, name = :name, yield_units = :yield, margin_pct = :margin,
                       prep_hours = :prep, notes = :notes, unit_weight_g = :weight, sale_price = :sale
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
    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, RecipeForm form) {
        return spec.param("category", form.categoryId()).param("name", form.name())
                .param("yield", dec(form.yieldUnits())).param("margin", dec(form.marginPct()))
                .param("prep", dec(form.prepHours())).param("notes", form.notes())
                .param("weight", dec(form.unitWeightG()))
                .param("sale", form.salePrice() == null ? null : dec(form.salePrice()));
    }

    /** {@inheritDoc} */
    @Override
    public void replaceItems(long recipeId, List<RecipeForm.Item> items) {
        jdbc.sql("DELETE FROM recipe_items WHERE recipe_id = :recipe").param("recipe", recipeId).update();
        for (RecipeForm.Item item : items) {
            jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:recipe, :ingredient, :qty)")
                    .param("recipe", recipeId).param("ingredient", item.ingredientId()).param("qty", dec(item.qty()))
                    .update();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM recipes WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> updateMenuFlags(OwnerId owner, long id, Boolean showOnMenu,
            List<String> tags, Boolean showIngredients) {

        // ── 1. Os selos vêm da lista fixa (letras e "_"), então o literal de array é seguro.
        String tagsLiteral = tags == null ? null : "{" + String.join(",", tags) + "}";

        // ── 2. Nulo mantém o valor atual.
        return jdbc.sql("""
                UPDATE recipes SET show_on_menu = COALESCE(CAST(:show AS boolean), show_on_menu),
                                   tags = COALESCE(CAST(:tags AS text[]), tags),
                                   show_ingredients = COALESCE(CAST(:ingredients AS boolean), show_ingredients)
                 WHERE id = :id AND user_id = :owner RETURNING id, show_on_menu, tags, show_ingredients""")
                .param("show", showOnMenu).param("tags", tagsLiteral).param("ingredients", showIngredients)
                .param("id", id).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(OwnerId owner, long id) {
        return jdbc.sql("SELECT 1 FROM recipes WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).query(Integer.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public void setSalePrice(OwnerId owner, long id, double salePrice) {
        jdbc.sql("UPDATE recipes SET sale_price = :price WHERE id = :id AND user_id = :owner")
                .param("price", dec(salePrice)).param("id", id).param("owner", owner.value()).update();
    }
}
