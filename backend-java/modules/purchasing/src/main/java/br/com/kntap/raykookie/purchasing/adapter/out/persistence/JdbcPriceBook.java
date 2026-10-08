package br.com.kntap.raykookie.purchasing.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.PriceBook;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cotações, produtos ligados e o preço dos ingredientes, com o mesmo SQL de {@code backend/lib/suppliers.ts}.
 */
@Repository
public class JdbcPriceBook implements PriceBook {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPriceBook(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Número como o Node o envia ao Postgres.
     *
     * @param value número
     * @return o decimal
     */
    static BigDecimal dec(double value) {
        return BigDecimal.valueOf(value);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Ingredient> ingredient(OwnerId owner, long id) {
        return jdbc.sql("""
                SELECT id, name, unit, package_cost,
                        package_size FROM ingredients WHERE id = :id AND user_id = :owner""")
                .param("id", id).param("owner", owner.value())
                .query((rs, row) -> new Ingredient(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).doubleValue(), rs.getBigDecimal(5).doubleValue()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public long linkProduct(LinkedProduct product) {
        return jdbc.sql("""
                INSERT INTO supplier_products (supplier_id, ingredient_id, external_url, name, pack_size, pack_unit,
                    price)
                VALUES (:supplier, :ingredient, :url, :name, :size, :unit, :price)
                ON CONFLICT (supplier_id, external_url) DO UPDATE SET ingredient_id = EXCLUDED.ingredient_id,
                       name = EXCLUDED.name, pack_size = EXCLUDED.pack_size, pack_unit = EXCLUDED.pack_unit,
                       price = EXCLUDED.price, seen_at = now()
                RETURNING id""")
                .param("supplier", product.supplierId()).param("ingredient", product.ingredientId())
                .param("url", product.url()).param("name", product.name()).param("size", dec(product.size()))
                .param("unit", product.unit()).param("price", dec(product.price())).query(Long.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public String addQuote(Quote quote) {
        return jdbc.sql("""
                INSERT INTO price_quotes (user_id, ingredient_id, supplier_id, supplier_product_id, price, pack_size,
                                          pack_unit, source)
                VALUES (:owner, :ingredient, :supplier, :product, :price, :size, :unit, :source) RETURNING id""")
                .param("owner", quote.owner().value()).param("ingredient", quote.ingredient())
                .param("supplier", quote.supplierId()).param("product", quote.productId())
                .param("price", dec(quote.price())).param("size", dec(quote.size())).param("unit", quote.unit())
                .param("source", quote.source()).query(Long.class).single().toString();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> latestBySupplier(OwnerId owner, long ingredient) {
        return jdbc.sql("""
                SELECT DISTINCT ON (pq.supplier_id) pq.id, pq.supplier_id, s.name AS supplier, s.kind,
                       pq.price::float8 AS price, pq.pack_size::float8 AS pack_size, pq.pack_unit, pq.source, pq.at,
                       (pq.price / pq.pack_size)::float8 AS per_base, sp.name AS product, sp.external_url AS url
                  FROM price_quotes pq JOIN suppliers s ON s.id = pq.supplier_id
                  LEFT JOIN supplier_products sp ON sp.id = pq.supplier_product_id
                 WHERE pq.user_id = :owner AND pq.ingredient_id = :ingredient AND pq.supplier_id IS NOT NULL
                 ORDER BY pq.supplier_id, pq.at DESC, pq.id DESC""")
                .param("owner", owner.value()).param("ingredient", ingredient).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> history(OwnerId owner, long ingredient) {
        return jdbc.sql("""
                SELECT pq.price::float8 AS price, pq.pack_size::float8 AS pack_size, pq.source, pq.at, s.name AS
                    supplier
                  FROM price_quotes pq LEFT JOIN suppliers s ON s.id = pq.supplier_id
                 WHERE pq.user_id = :owner AND pq.ingredient_id = :ingredient ORDER BY pq.at DESC,
                         pq.id DESC LIMIT 60""")
                .param("owner", owner.value()).param("ingredient", ingredient).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> ingredientRow(OwnerId owner, long id) {
        return jdbc.sql("""
                SELECT id, name, unit, package_cost::float8 AS package_cost, package_size::float8 AS package_size,
                       price_updated_at
                  FROM ingredients WHERE id = :id AND user_id = :owner""")
                .param("id", id).param("owner", owner.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Quote> quote(OwnerId owner, long ingredient, long quoteId) {
        return jdbc.sql("""
                SELECT price, pack_size, pack_unit, supplier_id FROM price_quotes
                 WHERE id = :id AND user_id = :owner AND ingredient_id = :ingredient""")
                .param("id", quoteId).param("owner", owner.value()).param("ingredient", ingredient)
                .query((rs, row) -> new Quote(owner, ingredient, rs.getObject(4) == null ? null : rs.getLong(4), null,
                        rs.getBigDecimal(1).doubleValue(), rs.getBigDecimal(2).doubleValue(), rs.getString(3), null))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void setIngredientPrice(long ingredient, double cost, double size) {
        jdbc.sql("UPDATE ingredients SET package_cost = :cost, package_size = :size WHERE id = :id")
                .param("cost", dec(cost)).param("size", dec(size)).param("id", ingredient).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Linked> linkedForRefresh() {
        return jdbc.sql("""
                SELECT sp.id, sp.supplier_id, sp.ingredient_id, sp.external_url, sp.name, sp.pack_size, sp.pack_unit,
                       sp.price, s.url AS base, s.user_id
                  FROM supplier_products sp JOIN suppliers s ON s.id = sp.supplier_id
                 WHERE sp.ingredient_id IS NOT NULL AND s.scraper = 'tray' ORDER BY s.id, sp.id""")
                .query((rs, row) -> new Linked(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6).doubleValue(), rs.getString(7),
                        rs.getBigDecimal(8).doubleValue(), rs.getString(9), new OwnerId(rs.getLong(10))))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public void markSeen(long productId, boolean available) {
        jdbc.sql("UPDATE supplier_products SET available = :available, seen_at = now() WHERE id = :id")
                .param("available", available).param("id", productId).update();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProductPrice(long productId, double price) {
        jdbc.sql("UPDATE supplier_products SET price = :price WHERE id = :id")
                .param("price", dec(price)).param("id", productId).update();
    }
}
