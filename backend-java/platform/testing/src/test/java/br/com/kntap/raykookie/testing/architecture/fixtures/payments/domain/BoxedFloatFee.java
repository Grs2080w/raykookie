package br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain;

/**
 * Taxa recebida como {@code Float} num parâmetro: também proibido.
 */
public final class BoxedFloatFee {

    /**
     * Aplica a taxa.
     *
     * @param fee taxa
     * @return a taxa em centavos
     */
    public long apply(Float fee) {
        return fee.longValue();
    }
}
