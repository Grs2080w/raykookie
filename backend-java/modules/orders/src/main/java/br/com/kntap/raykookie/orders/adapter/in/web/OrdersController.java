package br.com.kntap.raykookie.orders.adapter.in.web;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.orders.api.ports.PaidOrderCanceller;
import br.com.kntap.raykookie.orders.application.ManualOrderUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tela Pedidos da dona: {@code /api/orders}, {@code /api/orders/{id}} e o cancelamento de pedido da loja.
 */
@RestController
public class OrdersController {

    /** Cancelamento sem o módulo de devoluções. */
    static final String CANCEL_UNAVAILABLE = "Não foi possível cancelar agora. Tente de novo em alguns minutos.";

    /** Casos de uso. */
    private final ManualOrderUseCases orders;

    /** Cancelamento de pedido da loja (módulo de devoluções). */
    private final ObjectProvider<PaidOrderCanceller> canceller;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param orders    casos de uso
     * @param canceller cancelamento de pedido da loja
     * @param actor     quem está chamando
     */
    public OrdersController(ManualOrderUseCases orders, ObjectProvider<PaidOrderCanceller> canceller,
            CurrentActor actor) {
        this.orders = orders;
        this.canceller = canceller;
        this.actor = actor;
    }

    /**
     * Pedidos da dona.
     *
     * @return as linhas, mais novas primeiro
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/orders")
    public List<Map<String, Object>> list() {
        return orders.list(actor.requireOwner());
    }

    /**
     * Registra um pedido à mão.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return orders.create(actor.requireOwner(), orEmpty(body));
    }

    /**
     * Edita uma linha.
     *
     * @param id   linha
     * @param body dados
     * @return a linha editada
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/orders/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return orders.update(actor.requireOwner(), id, orEmpty(body));
    }

    /**
     * Exclui um pedido registrado à mão.
     *
     * @param id linha
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/orders/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        orders.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }

    /**
     * A dona cancela um pedido da loja; se já estava pago, devolve o que resta.
     *
     * @param id   pedido da loja
     * @param body {@code code} e {@code note}
     * @return o resultado do cancelamento
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/orders/shop/{id}/cancelar")
    public Map<String, Object> cancelShopOrder(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        PaidOrderCanceller available = canceller.getIfAvailable();
        if (available == null) {
            throw new DomainException(CANCEL_UNAVAILABLE, 503);
        }
        Map<String, Object> fields = orEmpty(body);
        return available.cancelByOwner(id, actor.requireOwner(), fields.get("code"), fields.get("note"));
    }

    /**
     * Corpo ausente vira vazio.
     *
     * @param body corpo
     * @return o corpo ou vazio
     */
    static Map<String, Object> orEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }
}
