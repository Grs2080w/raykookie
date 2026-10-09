package br.com.kntap.raykookie.orders.api.ports;

/**
 * Antes de cancelar um pedido não pago (implementada pelo módulo de pagamentos): confere se o Pix pendente foi pago
 * agora (e, se foi, confirma pelo caminho único de confirmação, G09) ou o desativa no Mercado Pago.
 */
public interface PendingChargeCanceller {

    /**
     * Confere e desativa o Pix pendente.
     *
     * @param orderId      pedido
     * @param mpPaymentId  pagamento Pix mais recente
     * @return verdadeiro se o Pix tinha sido pago e o pedido agora está pago
     */
    boolean settleBeforeCancel(long orderId, String mpPaymentId);
}
