package br.com.kntap.raykookie.orders.application.ports;

import java.util.List;
import java.util.Map;

/**
 * Histórico do pedido (tabela {@code order_events}).
 */
public interface OrderEvents {

    /**
     * Grava um evento.
     *
     * @param orderId pedido da loja
     * @param actor   quem
     * @param type    o quê
     * @param data    detalhes
     */
    void log(long orderId, String actor, String type, Map<String, Object> data);

    /**
     * Eventos do pedido, do mais antigo ao mais novo.
     *
     * @param orderId pedido da loja
     * @return {@code at}, {@code actor}, {@code type} e {@code data}
     */
    List<Map<String, Object>> of(long orderId);
}
