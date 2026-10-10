package br.com.kntap.raykookie.notifications.api.events;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;

/**
 * Aviso para os celulares de um cliente (app da loja), mandado depois do commit.
 */
public sealed interface CustomerNotice {

    /**
     * O pedido do aviso.
     *
     * @return o pedido
     */
    OrderRef order();

    /**
     * O pedido de que o aviso fala.
     *
     * @param id          id do pedido da loja
     * @param code        código público
     * @param customer    cliente
     * @param total       total
     * @param fulfillment {@code retirada} ou {@code entrega}
     * @param timeWindow  {@code manha}, {@code tarde} ou {@code noite}
     * @param paid        já foi pago
     */
    record OrderRef(long id, int code, CustomerId customer, Money total, String fulfillment, String timeWindow,
            boolean paid) {
    }

    /**
     * Pagamento confirmado.
     *
     * @param order pedido
     */
    record Paid(OrderRef order) implements CustomerNotice {
    }

    /**
     * Cartão que estava em análise no Mercado Pago foi recusado (nada foi cobrado).
     *
     * @param order  pedido
     * @param reason motivo da recusa, em português
     */
    record CardRefused(OrderRef order, String reason) implements CustomerNotice {
    }

    /**
     * Mudança de etapa (em preparo, pronto, entregue).
     *
     * @param order  pedido
     * @param status novo status da linha do painel
     */
    record Status(OrderRef order, String status) implements CustomerNotice {
    }

    /**
     * Cancelado pela loja.
     *
     * @param order    pedido
     * @param reason   motivo
     * @param refunded valor que volta ao cliente (zero = nada)
     * @param card     pago com cartão: o dinheiro volta como estorno no cartão, não por Pix
     */
    record StoreCancel(OrderRef order, String reason, Money refunded, boolean card) implements CustomerNotice {
    }

    /**
     * Novidade da devolução.
     *
     * @param order     pedido
     * @param kind      {@code aprovada}, {@code negada} ou {@code concluida}
     * @param amount    valor aprovado/devolvido
     * @param requested valor pedido (aprovada)
     * @param reason    motivo (negada)
     * @param card      pago com cartão: o dinheiro volta como estorno no cartão, não por Pix
     */
    record Refund(OrderRef order, String kind, Money amount, Money requested, String reason, boolean card)
            implements CustomerNotice {
    }
}
