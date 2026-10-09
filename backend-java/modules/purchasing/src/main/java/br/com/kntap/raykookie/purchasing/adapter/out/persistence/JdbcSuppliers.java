package br.com.kntap.raykookie.purchasing.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.Suppliers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fornecedores com o mesmo SQL de {@code backend/lib/suppliers.ts}.
 */
@Repository
public class JdbcSuppliers implements Suppliers {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcSuppliers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner) {
        return jdbc.sql("""
                SELECT id, name, kind, url, address, notes, scraper, last_ok_at, last_error
                  FROM suppliers WHERE user_id = :owner ORDER BY lower(name)""")
                .param("owner", owner.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(OwnerId owner, Form form) {
        return params(jdbc.sql("""
                INSERT INTO suppliers (user_id, name, kind, url, address, notes, scraper)
                VALUES (:owner, :name, :kind, :url, :address, :notes, :scraper) RETURNING *"""), form)
                .param("owner", owner.value()).query(NodeRows.ROW).single();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> update(OwnerId owner, long id, Form form) {
        return params(jdbc.sql("""
                UPDATE suppliers SET name = :name, kind = :kind, url = :url, address = :address, notes = :notes,
                       scraper = :scraper, last_error = ''
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
    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, Form form) {
        return spec.param("name", form.name()).param("kind", form.kind()).param("url", form.url())
                .param("address", form.address()).param("notes", form.notes()).param("scraper", form.scraper());
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM suppliers WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(OwnerId owner, long id) {
        return jdbc.sql("SELECT 1 FROM suppliers WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).query(Integer.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public List<Site> searchableSites(OwnerId owner) {
        return jdbc.sql("""
                SELECT id, name, url FROM suppliers
                 WHERE user_id = :owner AND kind = 'site' AND scraper IS NOT NULL ORDER BY lower(name)""")
                .param("owner", owner.value())
                .query((rs, row) -> new Site(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    /** {@inheritDoc} */
    @Override
    public void markOk(long id) {
        jdbc.sql("UPDATE suppliers SET last_ok_at = now(), last_error = '' WHERE id = :id").param("id", id).update();
    }

    /** {@inheritDoc} */
    @Override
    public void markError(long id, String error) {
        jdbc.sql("UPDATE suppliers SET last_error = :error WHERE id = :id").param("error", error).param("id", id)
                .update();
    }
}
