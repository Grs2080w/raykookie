package br.com.kntap.raykookie.payments.domain;

/**
 * Desfecho de um pagamento aprovado aplicado a um pedido da loja.
 */
public enum ConfirmationOutcome {

    /** Pago agora. */
    PAID_NOW,

    /** Reenvio do mesmo pagamento: nada mudou. */
    ALREADY_PAID,

    /** Pagamento que não é deste pedido ou não cobre o total. */
    REJECTED,

    /** Segunda cobrança de um pedido já pago: devolvida inteira. */
    DUPLICATE_REFUNDED,

    /** Pedido já cancelado: pago só para devolver inteiro. */
    PAID_AFTER_CANCEL_REFUNDED;

    /**
     * Diz se o pedido está pago depois disto.
     *
     * @return verdadeiro se pago (agora, antes, ou para devolver)
     */
    public boolean paid() {
        return this != REJECTED;
    }
}
