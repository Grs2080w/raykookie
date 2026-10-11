package br.com.kntap.raykookie.refunds.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.refunds.domain.RefundAmounts;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * O pedido da loja visto pelas devoluções ({@code loadOrder} de {@code backend/lib/refunds.ts}) e as mudanças que
 * as devoluções fazem nele.
 */
public interface RefundOrders {

    /**
     * Lê o pedido, os itens, as solicitações e o que já está comprometido.
     *
     * @param orderId pedido
     * @param lock    com {@code FOR UPDATE} no pedido e nas linhas
     * @return o pedido
     */
    Optional<OrderState> load(long orderId, boolean lock);

    /**
     * Cancela as linhas ainda abertas (as entregues ficam como estão).
     *
     * @param orderId pedido
     * @return quantas mudaram
     */
    int cancelOpenLines(long orderId);

    /**
     * Marca o pedido como cancelado.
     *
     * @param orderId pedido
     * @param label   rótulo do motivo
     * @param code    código do motivo
     * @param actor   {@code cliente}, {@code loja} ou {@code sistema}
     * @param note    observação
     */
    void markCancelled(long orderId, String label, String code, String actor, String note);

    /**
     * Soma uma devolução concluída (nunca passa do total).
     *
     * @param orderId pedido
     * @param amount  valor
     */
    void addRefunded(long orderId, Money amount);

    /**
     * Pedido para as devoluções.
     *
     * @param id             pedido
     * @param owner          dona
     * @param customer       cliente
     * @param code           número que o cliente vê
     * @param contactName    nome
     * @param total          total
     * @param paidAt         quando foi pago
     * @param paidMpId       pagamento que pagou
     * @param paymentIds     pagamentos gerados
     * @param cancelled      se foi cancelado
     * @param items          itens
     * @param refundedTotal  já devolvido
     * @param deliveredAt    quando foi entregue
     * @param paymentMethod  forma de pagamento
     * @param manual         pedido registrado à mão e pago por link
     * @param statuses       situação das linhas
     * @param liveRequest    há solicitação do cliente (não desistida)
     * @param pendingRequest há solicitação aguardando decisão
     * @param committed      aprovado e ainda não concluído
     * @param fulfillment    retirada ou entrega
     * @param timeWindow     período
     */
    record OrderState(long id, OwnerId owner, CustomerId customer, int code, String contactName, Money total,
            Instant paidAt, String paidMpId, List<String> paymentIds, boolean cancelled,
            List<RefundRequestForm.OrderedItem> items, Money refundedTotal, Instant deliveredAt, String paymentMethod,
            boolean manual, List<String> statuses, boolean liveRequest, boolean pendingRequest, Money committed,
            String fulfillment, String timeWindow) {

        /**
         * Diz se foi pago.
         *
         * @return verdadeiro se pago
         */
        public boolean paid() {
            return paidAt != null;
        }

        /**
         * O que ainda pode voltar.
         *
         * @return o restante
         */
        public Money left() {
            return RefundAmounts.left(total, refundedTotal, committed);
        }

        /**
         * Diz se foi pago com cartão (a devolução vira estorno no cartão, feito pelo Mercado Pago).
         *
         * @return verdadeiro se cartão
         */
        public boolean card() {
            return "cartao".equals(paymentMethod);
        }

        /**
         * O pedido nos avisos ao cliente.
         *
         * @return a referência
         */
        public CustomerNotice.OrderRef ref() {
            return new CustomerNotice.OrderRef(id, code, customer, total, fulfillment, timeWindow, paid());
        }
    }
}
