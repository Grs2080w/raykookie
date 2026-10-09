package br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain;

import br.com.kntap.raykookie.testing.architecture.fixtures.payments.application.LeakyUseCase;

/**
 * Domínio que chama a camada de aplicação: proibido.
 */
public final class LeakyDomain {

    /**
     * Usa o caso de uso.
     *
     * @param useCase caso de uso
     */
    public void run(LeakyUseCase useCase) {
        useCase.run(null);
    }
}
