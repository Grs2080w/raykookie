package br.com.kntap.raykookie.orders.api;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Optional;

/**
 * Pedido da loja com trava de linha, para a confirmação de pagamento (o único caminho que marca pago, G02/G09).
 */
public interface ShopOrderLocking {

    /**
     * Lê o pedido com {@code SELECT … FOR UPDATE} (na transação de quem chama).
     *
     * @param orderId pedido da loja
     * @return o pedido
     */
    Optional<PayableOrder> lockById(long orderId);

    /**
     * Marca o pedido e as linhas da tela Pedidos como pagos.
     *
     * @param orderId      pedido da loja
     * @param paymentId    pagamento aprovado
     * @param installments parcelas (nulo = não muda)
     */
    void markPaid(long orderId, String paymentId, Integer installments);

    /**
     * Pedido a pagar.
     *
     * @param id            pedido
     * @param owner         dona
     * @param customer      cliente
     * @param code          número que o cliente vê
     * @param contactName   nome
     * @param total         total
     * @param paid          se já foi pago
     * @param paidPaymentId pagamento que pagou (nulo = não pago, ou pago antes de o id ser guardado)
     * @param paymentIds    pagamentos gerados para o pedido
     * @param cancelled     se foi cancelado
     * @param items         quantos itens
     * @param fulfillment   retirada ou entrega
     * @param timeWindow    período
     */
    record PayableOrder(long id, OwnerId owner, CustomerId customer, int code, String contactName, Money total,
            boolean paid, String paidPaymentId, List<String> paymentIds, boolean cancelled, int items,
                    String fulfillment,
            String timeWindow) {

        /**
         * Diz se o pagamento foi gerado para este pedido.
         *
         * @param paymentId pagamento
         * @return verdadeiro se for
         */
        public boolean ownsPayment(String paymentId) {
            return paymentIds.contains(paymentId);
        }

        /**
         * Diz se foi pago com este pagamento (pedido antigo, pago sem o id guardado, conta como este).
         *
         * @param paymentId pagamento
         * @return verdadeiro se for
         */
        public boolean paidWith(String paymentId) {
            return paidPaymentId == null || paymentId.equals(paidPaymentId);
        }
    }
}
