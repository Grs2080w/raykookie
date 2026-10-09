package br.com.kntap.raykookie.catalog.adapter.out.persistence;

import static br.com.kntap.raykookie.catalog.adapter.out.persistence.JdbcIngredients.dec;

import br.com.kntap.raykookie.catalog.application.ports.FixedCosts;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Gastos fixos com o mesmo SQL das rotas {@code /api/fixed-costs}.
 */
@Repository
public class JdbcFixedCosts implements FixedCosts {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcFixedCosts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql("SELECT * FROM fixed_costs WHERE user_id = :owner ORDER BY lower(name)")
                .param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, SimpleForms.FixedCost form) {
        return jdbc.sql("""
                INSERT INTO fixed_costs (user_id, category_id, name, kind, amount)
                VALUES (:owner, :category, :name, :kind, :amount) RETURNING *""")
                .param("owner", owner.value()).param("category", form.categoryId()).param("name", form.name())
                .param("kind", form.kind()).param("amount", dec(form.amount())).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(OwnerId owner, long id, SimpleForms.FixedCost form) {
        return jdbc.sql("""
                UPDATE fixed_costs SET category_id = :category, name = :name, kind = :kind, amount = :amount
                 WHERE id = :id AND user_id = :owner RETURNING *""")
                .param("id", id).param("owner", owner.value()).param("category", form.categoryId())
                .param("name", form.name()).param("kind", form.kind()).param("amount", dec(form.amount()))
                .query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM fixed_costs WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public double total(OwnerId owner) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM fixed_costs WHERE user_id = :owner")
                .param("owner", owner.value()).query(BigDecimal.class).single().doubleValue();
    }
}
