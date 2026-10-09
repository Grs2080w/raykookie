package br.com.kntap.raykookie.identity.api;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;

/**
 * O que os outros módulos podem pedir ao {@code identity}.
 */
public interface IdentityApi {

    /**
     * Dona de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id da conta, se a sessão existir e não tiver vencido
     */
    Optional<OwnerId> ownerBySessionHash(String tokenHash);
}
