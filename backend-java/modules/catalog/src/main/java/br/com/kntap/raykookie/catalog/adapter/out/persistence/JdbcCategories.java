package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.application.ports.Categories;
import br.com.kntap.raykookie.catalog.domain.DefaultCategories;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Categorias com o mesmo SQL de {@code backend/lib/queries.ts} e das rotas {@code /api/categories}.
 */
@Repository
public class JdbcCategories implements Categories {

    /** Categorias com a contagem de usos (ingredientes, receitas e gastos). */
    private static final String LIST = """
            SELECT c.id, c.kind, c.name, c.icon, c.color,
                   (SELECT count(*)::int FROM ingredients i WHERE i.category_id = c.id)
                 + (SELECT count(*)::int FROM recipes r WHERE r.category_id = c.id)
                 + (SELECT count(*)::int FROM fixed_costs f WHERE f.category_id = c.id) AS uses
              FROM categories c
             WHERE c.user_id = :owner AND (CAST(:kind AS text) IS NULL OR c.kind = :kind)
             ORDER BY c.kind, lower(c.name)""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcCategories(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner, String kind) {
        return jdbc.sql(LIST).param("owner", owner.value()).param("kind", kind).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, String kind, SimpleForms.Category category) {
        return jdbc.sql("""
                INSERT INTO categories (user_id, kind, name, icon, color) VALUES (:owner, :kind, :name, :icon, :color)
                RETURNING *""")
                .param("owner", owner.value()).param("kind", kind).param("name", category.name())
                .param("icon", category.icon()).param("color", category.color())
                .query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(OwnerId owner, long id, SimpleForms.Category category) {
        return jdbc.sql("""
                UPDATE categories SET name = :name, icon = :icon, color = :color
                 WHERE id = :id AND user_id = :owner RETURNING *""")
                .param("id", id).param("owner", owner.value()).param("name", category.name())
                .param("icon", category.icon()).param("color", category.color())
                .query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM categories WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public void createAll(OwnerId owner, List<DefaultCategories.Seed> seeds) {
        for (DefaultCategories.Seed seed : seeds) {
            jdbc.sql("INSERT INTO categories (user_id, kind, name, icon, color) "
                    + "VALUES (:owner, :kind, :name, :icon, :color)")
                    .param("owner", owner.value()).param("kind", seed.kind()).param("name", seed.name())
                    .param("icon", seed.icon()).param("color", seed.color()).update();
        }
    }
}
