package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.api.events.OwnerRegistered;
import br.com.kntap.raykookie.security.ShopOwner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Na instalação nova, a conta cadastrada vira a dona da loja — só depois do commit, para a dona nunca
 * apontar para uma conta que um rollback apagou.
 */
@Component
public class ShopOwnerClaimer {

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /**
     * Cria o ouvinte.
     *
     * @param shopOwner a dona da loja
     */
    public ShopOwnerClaimer(ShopOwner shopOwner) {
        this.shopOwner = shopOwner;
    }

    /**
     * Torna a conta cadastrada a dona.
     *
     * @param event o cadastro
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOwnerRegistered(OwnerRegistered event) {
        shopOwner.claim(event.ownerId());
    }
}
