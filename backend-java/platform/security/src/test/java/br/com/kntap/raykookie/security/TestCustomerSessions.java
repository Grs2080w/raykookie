package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Nos testes deste módulo, as sessões de cliente lidas direto da tabela do Node (em produção, quem lê é o
 * módulo {@code customers}).
 */
@Component
class TestCustomerSessions implements CustomerSessions {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria a leitura.
     *
     * @param jdbc cliente JDBC
     */
    TestCustomerSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cliente de uma sessão válida.
     *
     * @param tokenHash hash do token
     * @return o id
     */
    @Override
    public Optional<CustomerId> customer(String tokenHash) {
        return jdbc.sql("SELECT customer_id FROM customer_sessions WHERE token_hash = :h AND expires_at > now()")
                .param("h", tokenHash).query(Long.class).optional().map(CustomerId::new);
    }
}
