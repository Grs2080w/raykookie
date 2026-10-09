package br.com.kntap.raykookie.orders.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Numeração dos pedidos da dona (G10): uma trava por dona e o próximo número.
 */
public interface OrderNumbers {

    /**
     * Trava a numeração da dona até o fim da transação ({@code pg_advisory_xact_lock}).
     *
     * @param owner dona
     */
    void lock(OwnerId owner);

    /**
     * Próximo número ({@code MAX(number) + 1}); chamar com a trava.
     *
     * @param owner dona
     * @return o número
     */
    int next(OwnerId owner);
}
