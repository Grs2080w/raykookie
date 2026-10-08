package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Descobre quem está por trás de um token: dona ou cliente. Os dois tipos de sessão ficam em tabelas
 * separadas, então um token de cliente nunca abre uma rota da dona, e vice-versa.
 */
@Component
public class ActorResolver {

    /** Sessões da dona. */
    private final OwnerSessions owners;

    /** Sessões de cliente. */
    private final CustomerSessions customers;

    /**
     * Cria o resolvedor.
     *
     * @param owners    sessões da dona
     * @param customers sessões de cliente
     */
    public ActorResolver(OwnerSessions owners, CustomerSessions customers) {
        this.owners = owners;
        this.customers = customers;
    }

    /**
     * Dona de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id da conta, se houver
     */
    public Optional<OwnerId> owner(String tokenHash) {
        return owners.owner(tokenHash);
    }

    /**
     * Cliente de uma sessão válida.
     *
     * @param tokenHash sha256 do token
     * @return o id do cliente, se houver
     */
    public Optional<CustomerId> customer(String tokenHash) {
        return customers.customer(tokenHash);
    }
}
