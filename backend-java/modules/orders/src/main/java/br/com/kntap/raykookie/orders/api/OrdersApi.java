package br.com.kntap.raykookie.orders.api;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
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

    /**
     * Sorteia um número de pedido livre para o cliente (4 ou 5 dígitos).
     *
     * @return o código
     */
    int newPublicCode();

    /**
     * O cliente cancela o próprio pedido, pelo corpo da rota ({@code reason_code} e {@code reason}).
     *
     * @param orderId  pedido da loja
     * @param customer cliente
     * @param body     corpo JSON
     * @return {@code {"ok":true,"state":…}}
     */
    Map<String, Object> cancelByCustomer(long orderId, CustomerId customer, Map<String, Object> body);

    /**
     * Histórico do pedido, do mais antigo ao mais novo.
     *
     * @param orderId pedido da loja
     * @return {@code at}, {@code actor}, {@code type} e {@code data}
     */
    List<Map<String, Object>> events(long orderId);

    /**
     * Pedidos da dona como a tela Pedidos mostra ({@code GET /api/orders}).
     *
     * @param owner dona
     * @return os pedidos
     */
    List<Map<String, Object>> ownerOrders(OwnerId owner);
}
