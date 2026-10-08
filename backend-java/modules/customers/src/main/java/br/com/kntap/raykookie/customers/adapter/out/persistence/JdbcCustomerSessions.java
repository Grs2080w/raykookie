package br.com.kntap.raykookie.customers.adapter.out.persistence;

import br.com.kntap.raykookie.customers.application.ports.CustomerSessionStore;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.security.CustomerSessions;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sessões de cliente na tabela {@code customer_sessions} do Node (só o sha256 do token; validade no relógio
 * do banco). Também é a fonte das sessões de cliente para o controle de acesso.
 */
@Repository
public class JdbcCustomerSessions implements CustomerSessionStore, CustomerSessions {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JdbcCustomerSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void create(CustomerId customer, String tokenHash, Instant expiresAt) {
        jdbc.sql("INSERT INTO customer_sessions (token_hash, customer_id, expires_at) VALUES (:hash, :id, :expires)")
                .param("hash", tokenHash).param("id", customer.value()).param("expires", Jdbc.timestamp(expiresAt))
                .update();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String tokenHash) {
        jdbc.sql("DELETE FROM customer_sessions WHERE token_hash = :hash").param("hash", tokenHash).update();
    }

    /** {@inheritDoc} */
    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM customer_sessions WHERE expires_at <= now()").update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CustomerId> customer(String tokenHash) {
        return jdbc.sql("SELECT customer_id FROM customer_sessions WHERE token_hash = :hash AND expires_at > now()")
                .param("hash", tokenHash).query(Long.class).optional().map(CustomerId::new);
    }
}
