package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import br.com.kntap.raykookie.catalog.application.ports.Combos;
import br.com.kntap.raykookie.catalog.domain.ComboForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Combos no Postgres.
 */
@Repository
public class JdbcCombos implements Combos {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcCombos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Combo> list(OwnerId owner) {

        // ── 1. Itens de todos os combos da dona.
        Map<Long, List<ComboForm.Item>> items = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT ci.combo_id, ci.recipe_id, ci.qty FROM combo_items ci JOIN combos c ON c.id = ci.combo_id
                 WHERE c.user_id = :owner ORDER BY ci.id""")
                .param("owner", owner.value())
                .query((rs, row) -> items.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                        .add(new ComboForm.Item(rs.getLong(2), rs.getInt(3))))
                .list();

        // ── 2. Os combos, cada um com os seus itens.
        return jdbc.sql("SELECT id, name, price::float8, show_on_menu FROM combos WHERE user_id = :owner "
                + "ORDER BY lower(name), id")
                .param("owner", owner.value())
                .query((rs, row) -> new Combo(rs.getLong(1), rs.getString(2),
                        rs.getObject(3) == null ? null : rs.getDouble(3), rs.getBoolean(4),
                        items.getOrDefault(rs.getLong(1), List.of())))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public long create(OwnerId owner, ComboForm form) {
        long id = jdbc.sql("""
                INSERT INTO combos (user_id, name, price, show_on_menu) VALUES (:owner, :name, :price, :show)
                RETURNING id""")
                .param("owner", owner.value()).param("name", form.name()).param("price", price(form))
                .param("show", form.showOnMenu()).query(Long.class).single();
        replaceItems(id, form.items());
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public boolean update(OwnerId owner, long id, ComboForm form) {
        int changed = jdbc.sql("""
                UPDATE combos SET name = :name, price = :price, show_on_menu = :show
                 WHERE id = :id AND user_id = :owner""")
                .param("id", id).param("owner", owner.value()).param("name", form.name()).param("price", price(form))
                .param("show", form.showOnMenu()).update();
        if (changed > 0) {
            replaceItems(id, form.items());
        }
        return changed > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM combos WHERE id = :id AND user_id = :owner").param("id", id)
                .param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public double discountPct(OwnerId owner) {
        return jdbc.sql("SELECT combo_discount_pct::float8 FROM settings WHERE user_id = :owner")
                .param("owner", owner.value()).query(Double.class).optional().orElse(10.0);
    }

    /** {@inheritDoc} */
    @Override
    public void setDiscountPct(OwnerId owner, double pct) {
        jdbc.sql("""
                INSERT INTO settings (user_id, combo_discount_pct) VALUES (:owner, :pct)
                ON CONFLICT (user_id) DO UPDATE SET combo_discount_pct = EXCLUDED.combo_discount_pct""")
                .param("owner", owner.value()).param("pct", BigDecimal.valueOf(pct)).update();
    }

    /**
     * Troca os itens do combo.
     *
     * @param id    combo
     * @param items itens
     */
    private void replaceItems(long id, List<ComboForm.Item> items) {
        jdbc.sql("DELETE FROM combo_items WHERE combo_id = :id").param("id", id).update();
        for (ComboForm.Item item : items) {
            jdbc.sql("INSERT INTO combo_items (combo_id, recipe_id, qty) VALUES (:id, :recipe, :qty)")
                    .param("id", id).param("recipe", item.recipeId()).param("qty", item.qty()).update();
        }
    }

    /**
     * Preço do formulário para o banco.
     *
     * @param form formulário
     * @return o preço, ou nulo
     */
    private static BigDecimal price(ComboForm form) {
        return form.price() == null ? null : BigDecimal.valueOf(form.price());
    }
}
