package br.com.kntap.raykookie.kernel.ids;

import br.com.kntap.raykookie.kernel.InvalidInputException;

/**
 * Identificador de pedido da loja (linha de {@code shop_orders}).
 *
 * @param value número positivo do pedido
 */
public record ShopOrderId(long value) {

    /**
     * Valida que o número é positivo.
     */
    public ShopOrderId {
        if (value <= 0) {
            throw new InvalidInputException("Identificador de pedido inválido.");
        }
    }
}
