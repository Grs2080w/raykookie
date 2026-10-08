package br.com.kntap.raykookie.testing.architecture.fixtures.payments.application;

import br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.out.persistence.PaymentStore;

/**
 * Caso de uso que conhece o adaptador de persistência: proibido.
 */
public class LeakyUseCase {

    /**
     * Grava pelo adaptador direto.
     *
     * @param store adaptador
     */
    public void run(PaymentStore store) {
        if (store != null) {
            store.save();
        }
    }
}
