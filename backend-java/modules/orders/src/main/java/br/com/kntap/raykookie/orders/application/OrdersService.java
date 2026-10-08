package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.application.ports.OrderEvents;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.orders.domain.PublicCodes;
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

    /** Cancelamento pelo cliente. */
    private final CustomerCancelUseCase cancel;

    /**
     * Cria o serviço.
     *
     * @param views      lista do cliente
     * @param events     histórico
     * @param shopOrders pedidos da loja
     * @param cancel     cancelamento pelo cliente
     */
    public OrdersService(CustomerOrderViews views, OrderEvents events, ShopOrders shopOrders,
            CustomerCancelUseCase cancel) {
        this.views = views;
        this.events = events;
        this.shopOrders = shopOrders;
        this.cancel = cancel;
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

    /** {@inheritDoc} */
    @Override
    public int newPublicCode() {
        return PublicCodes.allocate(shopOrders::codeTaken);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> cancelByCustomer(long orderId, CustomerId customer, Map<String, Object> body) {
        return cancel.cancel(orderId, customer, body);
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> events(long orderId) {
        return events.of(orderId);
    }
}
