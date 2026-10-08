package br.com.kntap.raykookie.orders.api;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * O que os pedidos oferecem aos outros módulos.
 */
public interface OrdersApi {

    /**
     * Pedidos do cliente no formato do Node ({@code getCustomerOrders}).
     *
     * @param customer cliente
     * @return os pedidos, mais novos primeiro
     */
    List<Map<String, Object>> customerOrders(CustomerId customer);

    /**
     * Um pedido do cliente no formato do Node.
     *
     * @param customer cliente
     * @param orderId  pedido
     * @return o pedido, se for do cliente
     */
    Optional<Map<String, Object>> customerOrder(CustomerId customer, long orderId);

    /**
     * Grava um evento no histórico do pedido (na transação de quem chama, se houver).
     *
     * @param orderId pedido
     * @param actor   {@code cliente}, {@code loja}, {@code sistema} ou {@code mercadopago}
     * @param type    tipo
     * @param data    dados
     */
    void logEvent(long orderId, String actor, String type, Map<String, Object> data);

    /**
     * Mantém {@code delivered_at} em dia (o prazo de devolução conta daqui).
     *
     * @param orderId pedido
     */
    void syncDelivered(long orderId);
}
