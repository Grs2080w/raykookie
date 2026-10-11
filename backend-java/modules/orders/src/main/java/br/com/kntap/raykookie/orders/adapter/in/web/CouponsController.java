package br.com.kntap.raykookie.orders.adapter.in.web;

import br.com.kntap.raykookie.orders.application.CouponUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cupons: a aba Cupons da dona e a conferência do cliente no último passo do pedido.
 */
@RestController
public class CouponsController {

    /** Casos de uso. */
    private final CouponUseCases coupons;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param coupons casos de uso
     * @param actor   quem está chamando
     */
    public CouponsController(CouponUseCases coupons, CurrentActor actor) {
        this.coupons = coupons;
        this.actor = actor;
    }

    /**
     * Cupons da dona.
     *
     * @return os cupons
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/coupons")
    public List<Map<String, Object>> list() {
        return coupons.list(actor.requireOwner());
    }

    /**
     * Novo cupom.
     *
     * @param body {@code code} (opcional), {@code percent}, {@code min_total} e {@code min_inclusive}
     * @return os cupons
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return coupons.create(actor.requireOwner(), body);
    }

    /**
     * Edita um cupom.
     *
     * @param id   cupom
     * @param body {@code percent}, {@code min_total}, {@code min_inclusive} e {@code active}
     * @return os cupons
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/coupons/{id}")
    public List<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return coupons.update(actor.requireOwner(), id, body);
    }

    /**
     * Exclui um cupom nunca usado.
     *
     * @param id cupom
     * @return os cupons
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/coupons/{id}")
    public List<Map<String, Object>> delete(@PathVariable long id) {
        return coupons.delete(actor.requireOwner(), id);
    }

    /**
     * Confere o cupom no último passo do pedido.
     *
     * @param body {@code code} e {@code items}
     * @return {@code code}, {@code percent}, {@code subtotal}, {@code discount} e {@code total}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/cupom")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        return coupons.check(actor.requireCustomer(), body);
    }
}
