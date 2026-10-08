package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Descobre quem está por trás de um token: dona ou cliente. Os dois tipos de sessão ficam em tabelas
 * separadas, então um token de cliente nunca abre uma rota da dona, e vice-versa.
 */
@Component
public class ActorResolver {

    /** Sessões da dona. */
    private final OwnerSessions owners;

    /** Sessões de cliente (o módulo {@code customers}); sem ele, nenhuma sessão de cliente vale. */
    private final ObjectProvider<CustomerSessions> customers;

    /**
     * Cria o resolvedor.
     *
     * @param owners    sessões da dona
     * @param customers sessões de cliente, se o módulo existir
     */
    public ActorResolver(OwnerSessions owners, ObjectProvider<CustomerSessions> customers) {
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
        CustomerSessions sessions = customers.getIfAvailable();
        return sessions == null ? Optional.empty() : sessions.customer(tokenHash);
    }
}
