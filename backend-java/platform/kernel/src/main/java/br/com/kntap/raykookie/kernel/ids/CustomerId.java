package br.com.kntap.raykookie.kernel.ids;

import br.com.kntap.raykookie.kernel.InvalidInputException;

/**
 * Identificador de cliente da loja (linha de {@code customers}).
 *
 * @param value número positivo do cadastro
 */
public record CustomerId(long value) {

    /**
     * Valida que o número é positivo.
     */
    public CustomerId {
        if (value <= 0) {
            throw new InvalidInputException("Identificador de cliente inválido.");
        }
    }
}
