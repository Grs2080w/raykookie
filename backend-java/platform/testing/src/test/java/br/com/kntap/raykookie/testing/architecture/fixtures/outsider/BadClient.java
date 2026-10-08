package br.com.kntap.raykookie.testing.architecture.fixtures.outsider;

import br.com.kntap.raykookie.testing.architecture.fixtures.shop.internal.ShopSecret;

/**
 * Cliente que fura o módulo "shop" e usa uma classe interna.
 */
public class BadClient {

    /**
     * Lê o valor interno.
     *
     * @param secret classe interna de outro módulo
     * @return o valor
     */
    public long read(ShopSecret secret) {
        return secret.value();
    }
}
