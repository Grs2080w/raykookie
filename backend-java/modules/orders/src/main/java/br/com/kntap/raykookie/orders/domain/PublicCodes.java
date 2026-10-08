package br.com.kntap.raykookie.orders.domain;

import java.security.SecureRandom;
import java.util.function.IntPredicate;

/**
 * Número que o cliente vê no pedido: aleatório, de 4 ou 5 dígitos (porte de {@code backend/lib/order-code.ts}).
 */
public final class PublicCodes {

    /** Menor código. */
    public static final int MIN = 1000;

    /** Maior código. */
    public static final int MAX = 99_999;

    /** Tentativas antes de desistir. */
    private static final int ATTEMPTS = 30;

    /** Sorteio. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private PublicCodes() {
    }

    /**
     * Sorteia um código livre.
     *
     * @param taken diz se o código já está em uso
     * @return o código
     * @throws IllegalStateException depois de 30 tentativas sem código livre
     */
    public static int allocate(IntPredicate taken) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int code = RANDOM.nextInt(MIN, MAX + 1);
            if (!taken.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Não foi possível gerar um código de pedido livre.");
    }
}
