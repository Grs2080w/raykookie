package br.com.kntap.raykookie.storefront.adapter.in.web;

import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.application.PickupUseCases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endereço de retirada: {@code /api/pickup} (público) e {@code /api/pickup-addresses} (dona).
 */
@RestController
public class PickupController {

    /** Casos de uso. */
    private final PickupUseCases pickups;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param pickups casos de uso
     * @param actor   quem está chamando
     */
    public PickupController(PickupUseCases pickups, CurrentActor actor) {
        this.pickups = pickups;
        this.actor = actor;
    }

    /**
     * Endereço de retirada atual da loja (o cliente vê antes de pedir).
     *
     * @return apelido, endereço e instruções (vazios se não houver)
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/pickup")
    public ResponseEntity<Map<String, Object>> current() {
        Optional<PickupAddress> active = pickups.publicPickup();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("label", active.map(PickupAddress::label).orElse(""));
        json.put("address", active.map(PickupAddress::address).orElse(""));
        json.put("instructions", active.map(PickupAddress::instructions).orElse(""));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=30").body(json);
    }

    /**
     * Endereços da dona.
     *
     * @return os endereços
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/pickup-addresses")
    public List<Map<String, Object>> list() {
        return pickups.list(actor.requireOwner()).stream().map(PickupAddress::json).toList();
    }

    /**
     * Novo endereço.
     *
     * @param body dados e, opcionalmente, {@code active}
     * @return o endereço criado
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/pickup-addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return pickups.create(actor.requireOwner(), body == null ? Map.of() : body).json();
    }

    /**
     * Edita e/ou escolhe como o atual.
     *
     * @param id   endereço
     * @param body dados e/ou {@code active}
     * @return o endereço como ficou
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/pickup-addresses/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return pickups.update(actor.requireOwner(), id, body == null ? Map.of() : body).json();
    }

    /**
     * Exclui um endereço.
     *
     * @param id endereço
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/pickup-addresses/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        pickups.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }
}
