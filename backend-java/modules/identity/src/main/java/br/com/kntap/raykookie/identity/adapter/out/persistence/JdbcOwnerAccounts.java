package br.com.kntap.raykookie.identity.adapter.out.persistence;

import br.com.kntap.raykookie.identity.application.ports.OwnerAccounts;
import br.com.kntap.raykookie.identity.domain.OwnerAccount;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Contas do painel na tabela {@code users} do Node.
 */
@Repository
public class JdbcOwnerAccounts implements OwnerAccounts {

    /** Colunas lidas. */
    private static final String COLUMNS = "SELECT id, username, password_hash FROM users ";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JdbcOwnerAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Conta pelo usuário ({@code lower(username)}, o índice único do Node).
     *
     * @param username usuário
     * @return a conta
     */
    @Override
    public Optional<OwnerAccount> findByUsername(String username) {
        return jdbc.sql(COLUMNS + "WHERE lower(username) = lower(:username)")
                .param("username", username)
                .query((rs, row) -> new OwnerAccount(new OwnerId(rs.getLong("id")), rs.getString("username"),
                        rs.getString("password_hash")))
                .optional();
    }

    /**
     * Conta pelo id.
     *
     * @param id id
     * @return a conta
     */
    @Override
    public Optional<OwnerAccount> findById(OwnerId id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id")
                .param("id", id.value())
                .query((rs, row) -> new OwnerAccount(new OwnerId(rs.getLong("id")), rs.getString("username"),
                        rs.getString("password_hash")))
                .optional();
    }

    /**
     * Diz se há contas.
     *
     * @return verdadeiro se {@code users} não está vazia
     */
    @Override
    public boolean any() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users)").query(Boolean.class).single();
    }

    /**
     * Cria a conta.
     *
     * @param username     usuário
     * @param passwordHash hash da senha
     * @return o id
     * @throws ConflictException se o usuário já existir (mesma mensagem do Node)
     */
    @Override
    public OwnerId create(String username, String passwordHash) {
        try {
            long id = jdbc.sql("INSERT INTO users (username, password_hash) VALUES (:username, :hash) RETURNING id")
                    .param("username", username)
                    .param("hash", passwordHash)
                    .query(Long.class)
                    .single();
            return new OwnerId(id);
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException("Esse usuário já existe. Escolha outro nome.");
        }
    }
}
