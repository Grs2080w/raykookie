package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.application.ports.OrderEvents;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Implementação de {@link OrdersApi}.
 */
@Service
public class OrdersService implements OrdersApi {

    /** Lista do cliente. */
    private final CustomerOrderViews views;

    /** Histórico. */
    private final OrderEvents events;

    /** Pedidos da loja. */
    private final ShopOrders shopOrders;

    /**
     * Cria o serviço.
     *
     * @param views      lista do cliente
     * @param events     histórico
     * @param shopOrders pedidos da loja
     */
    public OrdersService(CustomerOrderViews views, OrderEvents events, ShopOrders shopOrders) {
        this.views = views;
        this.events = events;
        this.shopOrders = shopOrders;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> customerOrders(CustomerId customer) {
        return views.of(customer, null);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> customerOrder(CustomerId customer, long orderId) {
        return views.of(customer, orderId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void logEvent(long orderId, String actor, String type, Map<String, Object> data) {
        events.log(orderId, actor, type, data);
    }

    /** {@inheritDoc} */
    @Override
    public void syncDelivered(long orderId) {
        shopOrders.syncDelivered(orderId);
    }
}
