package br.com.kntap.raykookie.storefront.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.application.ports.PickupAddresses;
import br.com.kntap.raykookie.storefront.domain.PickupForm;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Endereços de retirada: a dona guarda até 20 e escolhe qual vale agora (só um ativo).
 */
@Service
public class PickupUseCases {

    /** Endereços guardados por dona. */
    static final int MAX_PICKUP = 20;

    /** Endereços. */
    private final PickupAddresses pickups;

    /** Dona da loja (para o endereço público). */
    private final ShopOwner shopOwner;

    /**
     * Cria os casos de uso.
     *
     * @param pickups   endereços
     * @param shopOwner dona da loja
     */
    public PickupUseCases(PickupAddresses pickups, ShopOwner shopOwner) {
        this.pickups = pickups;
        this.shopOwner = shopOwner;
    }

    /**
     * Endereço de retirada atual da loja, para o cliente.
     *
     * @return o endereço ativo, se houver
     */
    public Optional<PickupAddress> publicPickup() {
        return shopOwner.isPending() ? Optional.empty() : pickups.active(shopOwner.id());
    }

    /**
     * Endereços da dona.
     *
     * @param owner dona
     * @return os endereços
     */
    public List<PickupAddress> list(OwnerId owner) {
        return pickups.list(owner);
    }

    /**
     * Novo endereço: o primeiro vira o ativo; os outros só com {@code active: true}.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return o endereço criado
     */
    @Transactional
    public PickupAddress create(OwnerId owner, Map<String, Object> body) {
        PickupForm form = PickupForm.from(body);
        int count = pickups.count(owner);
        if (count >= MAX_PICKUP) {
            throw new InvalidInputException("Você pode guardar até " + MAX_PICKUP
                    + " endereços. Exclua algum para adicionar outro.");
        }
        boolean active = count == 0 || Boolean.TRUE.equals(body.get("active"));
        if (active) {
            pickups.deactivateOthers(owner, 0);
        }
        return pickups.create(owner, form, active);
    }

    /**
     * Edita o endereço e/ou o escolhe como o atual.
     *
     * @param owner dona
     * @param id    endereço
     * @param body  campos do endereço e/ou {@code active: true}
     * @return o endereço como ficou
     * @throws NotFoundException se não for da dona
     */
    @Transactional
    public PickupAddress update(OwnerId owner, long id, Map<String, Object> body) {

        // ── 1. Endereço da dona; edição valida o formulário inteiro.
        if (pickups.find(owner, id).isEmpty()) {
            throw new NotFoundException("Endereço não encontrado.");
        }
        PickupForm form = body.keySet().stream().anyMatch(PickupForm.FIELDS::contains) ? PickupForm.from(body) : null;

        // ── 2. Troca o ativo e/ou os dados.
        if (Boolean.TRUE.equals(body.get("active"))) {
            pickups.deactivateOthers(owner, id);
            pickups.activate(owner, id);
        }
        if (form != null) {
            pickups.update(owner, id, form);
        }
        return pickups.find(owner, id).orElseThrow();
    }

    /**
     * Exclui um endereço; o ativo só sai se for o único.
     *
     * @param owner dona
     * @param id    endereço
     * @throws ConflictException se for o ativo e houver outros
     */
    public void delete(OwnerId owner, long id) {
        Optional<PickupAddress> found = pickups.find(owner, id);
        if (found.isEmpty()) {
            return;
        }
        if (found.get().active() && pickups.count(owner) > 1) {
            throw new ConflictException("Este é o endereço de retirada atual. Escolha outro como atual antes de "
                    + "excluir este.");
        }
        pickups.delete(owner, id);
    }
}
