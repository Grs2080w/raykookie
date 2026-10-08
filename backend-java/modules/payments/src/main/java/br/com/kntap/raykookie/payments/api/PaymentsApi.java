package br.com.kntap.raykookie.payments.api;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.List;
import java.util.Optional;

/**
 * O que pagamentos oferece aos outros módulos (devoluções).
 */
public interface PaymentsApi {

    /**
     * Devolve um pagamento no Mercado Pago. A chave de idempotência impede devolver duas vezes.
     *
     * @param paymentId      pagamento
     * @param amountOrNull   valor (nulo = tudo)
     * @param idempotencyKey chave de idempotência
     * @return a devolução criada
     * @throws GatewayException se o Mercado Pago recusar ou não responder
     */
    GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey);

    /**
     * Devoluções de um pagamento no Mercado Pago.
     *
     * @param paymentId pagamento
     * @return as devoluções
     * @throws GatewayException se o Mercado Pago não responder
     */
    List<GatewayRefund> refunds(String paymentId);

    /**
     * Desativa os Pix ainda pendentes (pedido cancelado). Falha não é erro: o Pix vence sozinho.
     *
     * @param paymentIds pagamentos
     */
    void cancelPendingCharges(List<String> paymentIds);

    /**
     * Convidado dono do pedido de um link de pagamento PAGO (promove na hora um link antigo).
     *
     * @param token token do link
     * @return o cliente e o pedido do app, ou vazio (token ruim, inexistente ou link não pago)
     */
    Optional<LinkGuest> linkGuest(String token);

    /**
     * Convidado e pedido de um link pago.
     *
     * @param customer    cliente convidado
     * @param shopOrderId pedido do app
     */
    record LinkGuest(CustomerId customer, long shopOrderId) {
    }
}
