package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.Optional;

/**
 * Porta: sessões de cliente (tabela {@code customer_sessions}).
 */
public interface CustomerSessions {

    /**
     * Cliente de uma sessão ainda válida.
     *
     * @param tokenHash sha256 do token
     * @return o id do cliente, se a sessão existir e não tiver vencido
     */
    Optional<CustomerId> customer(String tokenHash);
}
