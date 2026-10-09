package br.com.kntap.raykookie.orders.adapter.in.web;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.orders.application.CustomerCancelUseCase;
import br.com.kntap.raykookie.orders.application.CustomerOrderViews;
import br.com.kntap.raykookie.orders.application.ShopOrderUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.ClientIpResolver;
import br.com.kntap.raykookie.web.NodeInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedidos do cliente na loja: {@code /api/cliente/pedidos} e o cancelamento.
 */
@RestController
public class CustomerOrdersController {

    /** Pedido inexistente. */
    static final String NOT_FOUND = "Pedido não encontrado.";

    /** Criação do pedido. */
    private final ShopOrderUseCases shopOrders;

    /** Lista do cliente. */
    private final CustomerOrderViews views;

    /** Cancelamento. */
    private final CustomerCancelUseCase cancel;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** IP real do cliente. */
    private final ClientIpResolver clientIp;

    /**
     * Cria o controller.
     *
     * @param shopOrders criação do pedido
     * @param views      lista do cliente
     * @param cancel     cancelamento
     * @param actor      quem está chamando
     * @param clientIp   IP real do cliente
     */
    public CustomerOrdersController(ShopOrderUseCases shopOrders, CustomerOrderViews views,
            CustomerCancelUseCase cancel, CurrentActor actor, ClientIpResolver clientIp) {
        this.shopOrders = shopOrders;
        this.views = views;
        this.cancel = cancel;
        this.actor = actor;
        this.clientIp = clientIp;
    }

    /**
     * Pedidos do cliente.
     *
     * @return os pedidos, mais novos primeiro
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/pedidos")
    public List<Map<String, Object>> list() {
        return views.of(actor.requireCustomer(), null);
    }

    /**
     * Faz um pedido.
     *
     * @param body    pedido
     * @param request requisição (IP real)
     * @return {@code id}, {@code code}, {@code pix} e {@code card}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return shopOrders.create(actor.requireCustomer(), OrdersController.orEmpty(body), clientIp.resolve(request));
    }

    /**
     * Cancela um pedido ainda não iniciado.
     *
     * @param id   pedido
     * @param body {@code reason_code} e {@code reason}
     * @return {@code {"ok":true,"state":…}}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/pedidos/{id}/cancelar")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String,
            Object> body) {
        long orderId = NodeInput.integer(id).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        return cancel.cancel(orderId, actor.requireCustomer(), OrdersController.orEmpty(body));
    }
}
