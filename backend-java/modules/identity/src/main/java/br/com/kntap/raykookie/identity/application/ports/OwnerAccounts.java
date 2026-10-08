package br.com.kntap.raykookie.identity.application.ports;

import br.com.kntap.raykookie.identity.domain.OwnerAccount;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;

/**
 * Porta de saída: contas do painel ({@code users}).
 */
public interface OwnerAccounts {

    /**
     * Conta pelo usuário, sem diferenciar maiúsculas.
     *
     * @param username usuário
     * @return a conta, se existir
     */
    Optional<OwnerAccount> findByUsername(String username);

    /**
     * Conta pelo id.
     *
     * @param id id
     * @return a conta, se existir
     */
    Optional<OwnerAccount> findById(OwnerId id);

    /**
     * Diz se existe alguma conta.
     *
     * @return verdadeiro se {@code users} não está vazia
     */
    boolean any();

    /**
     * Cria uma conta.
     *
     * @param username     usuário
     * @param passwordHash hash da senha
     * @return o id criado
     * @throws br.com.kntap.raykookie.kernel.ConflictException se o usuário já existir
     */
    OwnerId create(String username, String passwordHash);
}
