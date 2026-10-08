package br.com.kntap.raykookie.kernel.ids;

import br.com.kntap.raykookie.kernel.InvalidInputException;

/**
 * Identificador da dona da loja (linha de {@code users}).
 *
 * @param value número positivo do cadastro
 */
public record OwnerId(long value) {

    /**
     * Valida que o número é positivo.
     */
    public OwnerId {
        if (value <= 0) {
            throw new InvalidInputException("Identificador de dona inválido.");
        }
    }
}
