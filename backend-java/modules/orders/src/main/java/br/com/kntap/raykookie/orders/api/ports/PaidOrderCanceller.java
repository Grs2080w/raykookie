package br.com.kntap.raykookie.orders.api.ports;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Map;

/**
 * Cancelamento que devolve o dinheiro (implementada pelo módulo de devoluções).
 */
public interface PaidOrderCanceller {

    /**
     * O cliente cancela um pedido já pago e ainda não iniciado: devolve o total na hora.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @param code     motivo
     * @param note     texto do cliente
     * @return {@code {"ok":true,"state":"cancelled","refunded":…}}
     */
    Map<String, Object> cancelPaidByCustomer(long orderId, CustomerId customer, String code, String note);

    /**
     * A dona cancela um pedido (pago ou não); se pago, devolve o que resta.
     *
     * @param orderId pedido
     * @param owner   dona
     * @param code    motivo
     * @param note    texto da dona
     * @return {@code {"ok":true,"refundId":…,"refunded":…}}
     */
    Map<String, Object> cancelByOwner(long orderId, OwnerId owner, Object code, Object note);
}
