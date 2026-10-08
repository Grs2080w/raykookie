package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Leitura das sessões nas tabelas que o Node já usa ({@code sessions}, {@code customer_sessions}),
 * com o mesmo critério de validade: {@code expires_at > now()} no relógio do banco.
 */
@Component
public class JdbcSessionStore implements OwnerSessions, CustomerSessions {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JdbcSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dona de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id da conta
     */
    @Override
    public Optional<OwnerId> owner(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM sessions WHERE token_hash = :hash AND expires_at > now()")
                .param("hash", tokenHash)
                .query(Long.class)
                .optional()
                .map(OwnerId::new);
    }

    /**
     * Conta pelo nome de usuário.
     *
     * @param username nome de usuário
     * @return o id da conta
     */
    @Override
    public Optional<OwnerId> ownerByUsername(String username) {
        return jdbc.sql("SELECT id FROM users WHERE lower(username) = lower(:username)")
                .param("username", username)
                .query(Long.class)
                .optional()
                .map(OwnerId::new);
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
