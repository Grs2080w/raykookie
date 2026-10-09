package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Nos testes deste módulo, as sessões da dona lidas direto das tabelas do Node (em produção, quem lê é o
 * módulo {@code identity}).
 */
@Component
class TestOwnerSessions implements OwnerSessions {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria a leitura.
     *
     * @param jdbc cliente JDBC
     */
    TestOwnerSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Dona de uma sessão válida.
     *
     * @param tokenHash hash do token
     * @return o id
     */
    @Override
    public Optional<OwnerId> owner(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM sessions WHERE token_hash = :h AND expires_at > now()")
                .param("h", tokenHash).query(Long.class).optional().map(OwnerId::new);
    }

    /**
     * Conta pelo nome.
     *
     * @param username nome
     * @return o id
     */
    @Override
    public Optional<OwnerId> ownerByUsername(String username) {
        return jdbc.sql("SELECT id FROM users WHERE lower(username) = lower(:u)")
                .param("u", username).query(Long.class).optional().map(OwnerId::new);
    }

    /**
     * Há contas.
     *
     * @return verdadeiro se users não está vazia
     */
    @Override
    public boolean hasAnyAccount() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users)").query(Boolean.class).single();
    }
}
