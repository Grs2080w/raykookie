package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Leitura das sessões de cliente ({@code customer_sessions}) com o critério do Node:
 * {@code expires_at > now()} no relógio do banco. Provisório: muda para o módulo {@code customers} na
 * Tarefa 4.2, como as sessões da dona mudaram para o {@code identity} na 4.1.
 */
@Component
public class JdbcCustomerSessions implements CustomerSessions {

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

    /**
     * Cliente de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id do cliente
     */
    @Override
    public Optional<CustomerId> customer(String tokenHash) {
        return jdbc.sql("SELECT customer_id FROM customer_sessions WHERE token_hash = :hash AND expires_at > now()")
                .param("hash", tokenHash)
                .query(Long.class)
                .optional()
                .map(CustomerId::new);
    }
}
