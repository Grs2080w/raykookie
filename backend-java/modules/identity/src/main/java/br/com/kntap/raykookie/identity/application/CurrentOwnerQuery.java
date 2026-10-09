package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.application.ports.OwnerAccounts;
import br.com.kntap.raykookie.identity.domain.OwnerAccount;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.UnauthorizedException;
import org.springframework.stereotype.Service;

/**
 * A conta da dona logada ({@code GET /api/me}).
 */
@Service
public class CurrentOwnerQuery {

    /** Contas. */
    private final OwnerAccounts accounts;

    /**
     * Cria a consulta.
     *
     * @param accounts contas
     */
    public CurrentOwnerQuery(OwnerAccounts accounts) {
        this.accounts = accounts;
    }

    /**
     * A conta pelo id da sessão.
     *
     * @param owner id da dona logada
     * @return a conta
     * @throws UnauthorizedException se a conta sumiu depois do login
     */
    public OwnerAccount byId(OwnerId owner) {
        return accounts.findById(owner)
                .orElseThrow(() -> new UnauthorizedException(UnauthorizedException.OWNER_MESSAGE));
    }
}
