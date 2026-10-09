package br.com.kntap.raykookie.storefront.api;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;

/**
 * O que a vitrine oferece aos outros módulos (pedidos da loja).
 */
public interface StorefrontApi {

    /**
     * O cardápio atual da loja.
     *
     * @return itens e grupos
     */
    Menu currentMenu();

    /**
     * O endereço de retirada ativo.
     *
     * @param owner dona
     * @return o endereço, se a dona já escolheu um
     */
    Optional<PickupAddress> activePickupAddress(OwnerId owner);
}
