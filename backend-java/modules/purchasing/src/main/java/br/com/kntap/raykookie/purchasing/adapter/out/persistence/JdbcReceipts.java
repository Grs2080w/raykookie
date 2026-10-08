package br.com.kntap.raykookie.purchasing.adapter.out.persistence;

import static br.com.kntap.raykookie.purchasing.adapter.out.persistence.JdbcPriceBook.dec;

import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.Receipts;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Notas lançadas e apelidos, com o mesmo SQL de {@code /api/nota/ler} e {@code /api/nota/importar}.
 */
@Repository
public class JdbcReceipts implements Receipts {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcReceipts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<NfceMatching.Ingredient> ingredients(OwnerId owner) {
        return jdbc.sql("""
                SELECT id, name, unit, package_size, package_cost FROM ingredients
                 WHERE user_id = :owner ORDER BY lower(name)""")
                .param("owner", owner.value())
                .query((rs, row) -> new NfceMatching.Ingredient(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).doubleValue(), rs.getBigDecimal(5).doubleValue()))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Long> aliases(OwnerId owner) {
        Map<String, Long> aliases = new HashMap<>();
        jdbc.sql("SELECT item_key, ingredient_id FROM receipt_aliases WHERE user_id = :owner")
                .param("owner", owner.value()).query(rs -> {
                    aliases.put(rs.getString(1), rs.getLong(2));
                });
        return aliases;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> importedAt(OwnerId owner, String key) {
        return jdbc.sql("SELECT created_at FROM receipt_imports WHERE user_id = :owner AND access_key = :key")
                .param("owner", owner.value()).param("key", key)
                .query((rs, row) -> NodeRows.timestamp(rs.getObject(1, OffsetDateTime.class).toInstant())).optional();
    }

    /** {@inheritDoc} */
    @Override
    public boolean register(OwnerId owner, Note note) {
        return jdbc.sql("""
                INSERT INTO receipt_imports (user_id, access_key, store, issued_on, total)
                VALUES (:owner, :key, :store, CAST(:date AS date), :total)
                ON CONFLICT (user_id, access_key) DO NOTHING RETURNING id""")
                .param("owner", owner.value()).param("key", note.key()).param("store", note.store())
                .param("date", note.date()).param("total", note.total() == null ? null : dec(note.total()))
                .query(Long.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PriceState> price(OwnerId owner, long ingredient) {
        return jdbc.sql("SELECT package_cost, package_size FROM ingredients WHERE id = :id AND user_id = :owner")
                .param("id", ingredient).param("owner", owner.value())
                .query((rs, row) -> new PriceState(rs.getBigDecimal(1).doubleValue(), rs.getBigDecimal(2)
                        .doubleValue()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PriceState> addStock(OwnerId owner, long ingredient, double qty, Double cost) {
        return jdbc.sql("""
                UPDATE ingredients SET stock_qty = round((stock_qty + :qty)::numeric, 3),
                       package_cost = COALESCE(CAST(:cost AS numeric), package_cost)
                 WHERE id = :id AND user_id = :owner RETURNING package_cost, package_size""")
                .param("qty", dec(qty)).param("cost", cost == null ? null : dec(cost)).param("id", ingredient)
                .param("owner", owner.value())
                .query((rs, row) -> new PriceState(rs.getBigDecimal(1).doubleValue(), rs.getBigDecimal(2)
                        .doubleValue()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void remember(OwnerId owner, String itemKey, long ingredient) {
        jdbc.sql("""
                INSERT INTO receipt_aliases (user_id, item_key, ingredient_id) VALUES (:owner, :key, :ingredient)
                ON CONFLICT (user_id, item_key) DO UPDATE SET ingredient_id = EXCLUDED.ingredient_id""")
                .param("owner", owner.value()).param("key", itemKey).param("ingredient", ingredient).update();
    }

    /** {@inheritDoc} */
    @Override
    public void finish(OwnerId owner, String key, int added, int created) {
        jdbc.sql("""
                UPDATE receipt_imports SET added = :added, created = :created
                 WHERE user_id = :owner AND access_key = :key""")
                .param("added", added).param("created", created).param("owner", owner.value()).param("key", key)
                .update();
    }
}
