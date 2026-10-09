package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.application.ports.PhotoStore;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fotos na tabela {@code recipe_photos}, como no Node (a troca por disco é a Tarefa 8.1).
 */
@Repository
public class JdbcPhotoStore implements PhotoStore {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPhotoStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void save(long recipeId, String contentType, byte[] data) {
        jdbc.sql("""
                INSERT INTO recipe_photos (recipe_id, content_type, data) VALUES (:recipe, :type, :data)
                ON CONFLICT (recipe_id) DO UPDATE SET content_type = EXCLUDED.content_type, data = EXCLUDED.data,
                                                      updated_at = now()""")
                .param("recipe", recipeId).param("type", contentType).param("data", data).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Photo> load(long recipeId, long ownerId) {
        return jdbc.sql("""
                SELECT p.content_type, p.data FROM recipe_photos p JOIN recipes r ON r.id = p.recipe_id
                 WHERE p.recipe_id = :recipe AND r.user_id = :owner""")
                .param("recipe", recipeId).param("owner", ownerId)
                .query((rs, row) -> new Photo(rs.getString(1), rs.getBytes(2))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long recipeId, long ownerId) {
        jdbc.sql("""
                DELETE FROM recipe_photos
                 WHERE recipe_id = :recipe AND recipe_id IN (SELECT id FROM recipes WHERE user_id = :owner)""")
                .param("recipe", recipeId).param("owner", ownerId).update();
    }
}
