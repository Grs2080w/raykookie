package br.com.kntap.raykookie.testing.architecture.fixtures.outsider;

import br.com.kntap.raykookie.testing.architecture.fixtures.shop.api.ShopApi;

/**
 * Cliente que usa só a API pública de "shop".
 */
public class GoodClient {

    /**
     * Lê o total pela API.
     *
     * @param shop API do módulo
     * @return o total
     */
    public long read(ShopApi shop) {
        return shop.total();
    }
}
