package br.com.kntap.raykookie.payments.api.ports;

/**
 * Devoluções pedidas por pagamentos (implementada pelo módulo de devoluções).
 */
public interface RefundRequester {

    /**
     * Pagamento que chegou depois do cancelamento: registra a devolução de tudo o que resta na transação de quem
     * chama e a manda ao Mercado Pago depois do commit.
     *
     * @param orderId pedido da loja
     * @return verdadeiro se uma devolução foi registrada
     */
    boolean refundLatePayment(long orderId);

    /**
     * Confere as devoluções de um pagamento no Mercado Pago e fecha as nossas (estorno ou devolução pelo painel).
     *
     * @param orderId   pedido da loja
     * @param paymentId pagamento
     */
    void reconcileRefunds(long orderId, String paymentId);
}
