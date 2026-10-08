package br.com.kntap.raykookie.storefront.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.domain.PickupForm;
import java.util.List;
import java.util.Optional;

/**
 * Endereços de retirada da dona (tabela {@code pickup_addresses}; só um ativo por dona).
 */
public interface PickupAddresses {

    /**
     * Endereços, o ativo primeiro e depois pelo apelido.
     *
     * @param owner dona
     * @return os endereços
     */
    List<PickupAddress> list(OwnerId owner);

    /**
     * O endereço ativo.
     *
     * @param owner dona
     * @return o endereço, se houver
     */
    Optional<PickupAddress> active(OwnerId owner);

    /**
     * Um endereço da dona.
     *
     * @param owner dona
     * @param id    endereço
     * @return o endereço, ou vazio se não for da dona
     */
    Optional<PickupAddress> find(OwnerId owner, long id);

    /**
     * Quantos endereços a dona guarda.
     *
     * @param owner dona
     * @return a quantidade
     */
    int count(OwnerId owner);

    /**
     * Desliga o ativo (menos o endereço indicado).
     *
     * @param owner  dona
     * @param except endereço que fica como está (0 = nenhum)
     */
    void deactivateOthers(OwnerId owner, long except);

    /**
     * Cria um endereço.
     *
     * @param owner  dona
     * @param form   dados
     * @param active se já nasce ativo
     * @return o endereço criado
     */
    PickupAddress create(OwnerId owner, PickupForm form, boolean active);

    /**
     * Liga o endereço como o atual.
     *
     * @param owner dona
     * @param id    endereço
     */
    void activate(OwnerId owner, long id);

    /**
     * Troca os dados do endereço.
     *
     * @param owner dona
     * @param id    endereço
     * @param form  dados
     */
    void update(OwnerId owner, long id, PickupForm form);

    /**
     * Apaga o endereço.
     *
     * @param owner dona
     * @param id    endereço
     */
    void delete(OwnerId owner, long id);
}
