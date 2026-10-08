package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;

/**
 * Porta: sessões da dona (tabela {@code sessions}).
 */
public interface OwnerSessions {

    /**
     * Dono de uma sessão ainda válida.
     *
     * @param tokenHash sha256 do token
     * @return o id da conta, se a sessão existir e não tiver vencido
     */
    Optional<OwnerId> owner(String tokenHash);

    /**
     * Id de uma conta pelo nome de usuário (sem diferenciar maiúsculas, como o índice do banco).
     *
     * @param username nome de usuário
     * @return o id, se a conta existir
     */
    Optional<OwnerId> ownerByUsername(String username);
}
