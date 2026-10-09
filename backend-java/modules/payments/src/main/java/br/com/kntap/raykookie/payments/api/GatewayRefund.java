package br.com.kntap.raykookie.payments.api;

import br.com.kntap.raykookie.kernel.Money;

/**
 * Devolução no Mercado Pago.
 *
 * @param id     id da devolução
 * @param amount valor
 * @param status {@code approved}, {@code in_process}…
 */
public record GatewayRefund(String id, Money amount, String status) {

    /**
     * Diz se a devolução já foi concluída.
     *
     * @return verdadeiro se {@code approved}
     */
    public boolean approved() {
        return "approved".equals(status);
    }
}
