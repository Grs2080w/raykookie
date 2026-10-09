package br.com.kntap.raykookie.identity.adapter.out.persistence;

import br.com.kntap.raykookie.identity.api.IdentityApi;
import br.com.kntap.raykookie.identity.application.ports.OwnerSessionStore;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.Jdbc;
import br.com.kntap.raykookie.security.OwnerSessions;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sessões da dona na tabela {@code sessions} do Node: só o sha256 do token, validade conferida no relógio
 * do banco ({@code expires_at > now()}), como o Node faz. Também é a fonte das sessões para o controle de
 * acesso ({@link OwnerSessions}) e para os outros módulos ({@link IdentityApi}).
 */
@Repository
public class JdbcOwnerSessions implements OwnerSessionStore, OwnerSessions, IdentityApi {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JdbcOwnerSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grava a sessão.
     *
     * @param owner     dona
     * @param tokenHash sha256 do token
     * @param expiresAt vencimento
     */
    @Override
    public void create(OwnerId owner, String tokenHash, Instant expiresAt) {
        jdbc.sql("INSERT INTO sessions (user_id, token_hash, expires_at) VALUES (:owner, :hash, :expires)")
                .param("owner", owner.value())
                .param("hash", tokenHash)
                .param("expires", Jdbc.timestamp(expiresAt))
                .update();
    }

    /**
     * Apaga a sessão.
     *
     * @param tokenHash sha256 do token
     */
    @Override
    public void delete(String tokenHash) {
        jdbc.sql("DELETE FROM sessions WHERE token_hash = :hash").param("hash", tokenHash).update();
    }

    /**
     * Apaga as vencidas.
     *
     * @return quantas
     */
    @Override
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM sessions WHERE expires_at <= now()").update();
    }

    /**
     * Dona de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id
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
     * Mesmo que {@link #owner(String)}, para os outros módulos.
     *
     * @param tokenHash sha256 do token
     * @return o id
     */
    @Override
    public Optional<OwnerId> ownerBySessionHash(String tokenHash) {
        return owner(tokenHash);
    }

    /**
     * Conta pelo usuário.
     *
     * @param username usuário
     * @return o id
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
     * Diz se há contas.
     *
     * @return verdadeiro se {@code users} não está vazia
     */
    @Override
    public boolean hasAnyAccount() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users)").query(Boolean.class).single();
    }
}
