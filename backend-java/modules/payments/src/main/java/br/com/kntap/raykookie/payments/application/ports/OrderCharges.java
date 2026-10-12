package br.com.kntap.raykookie.payments.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Colunas de cobrança dos pedidos da loja ({@code pix_*}, {@code card_attempts}, {@code mp_payment_ids}).
 */
public interface OrderCharges {

    /**
     * O pedido do cliente, para cobrar de novo (Pix ou cartão).
     *
     * @param orderId  pedido
     * @param customer cliente
     * @return o pedido
     */
    Optional<ChargeTarget> target(long orderId, CustomerId customer);

    /**
     * Grava o Pix gerado.
     *
     * @param orderId   pedido
     * @param paymentId pagamento
     * @param code      copia e cola
     * @param qrBase64  QR em base64
     * @param expiresAt validade
     * @param attempt   número do Pix
     */
    void savePix(long orderId, String paymentId, String code, String qrBase64, Instant expiresAt, int attempt);

    /**
     * Junta um pagamento aos do pedido (é a guarda que a confirmação confere).
     *
     * @param orderId   pedido
     * @param paymentId pagamento
     */
    void addPaymentId(long orderId, String paymentId);

    /**
     * Conta uma tentativa de cartão sem aprovação.
     *
     * @param orderId   pedido
     * @param paymentId pagamento pendente a guardar (nulo = recusa)
     */
    void countCardAttempt(long orderId, String paymentId);

    /**
     * Pedidos dos últimos 2 dias ainda sem pagamento e com cobrança gerada (conciliação).
     *
     * @return id e pagamentos de cada um
     */
    List<OpenCharge> openCharges();

    /**
     * Acha o pedido a que um pagamento pertence (qualquer um dos pagamentos gerados para ele).
     *
     * @param paymentId id do pagamento no Mercado Pago
     * @return o id do pedido, se existir
     */
    Optional<Long> orderIdByPayment(String paymentId);

    /**
     * Pedido a cobrar.
     *
     * @param id            pedido
     * @param owner         dona
     * @param code          número que o cliente vê
     * @param total         total
     * @param contactName   nome
     * @param email         e-mail do cliente
     * @param phone         telefone para contato (só dígitos)
     * @param paymentMethod forma de pagamento
     * @param paid          se já foi pago
     * @param mpPaymentId   Pix mais recente
     * @param pixQrCode     copia e cola atual
     * @param pixQrBase64   QR atual
     * @param pixExpiresAt  validade do Pix atual
     * @param pixAttempts   Pix gerados
     * @param cardAttempts  tentativas de cartão
     * @param statuses      situação de cada item
     * @param items         quantos itens
     */
    record ChargeTarget(long id, OwnerId owner, int code, Money total, String contactName, String email, String phone,
            String paymentMethod, boolean paid, String mpPaymentId, String pixQrCode, String pixQrBase64,
            Instant pixExpiresAt, int pixAttempts, int cardAttempts, List<String> statuses, int items) {

        /**
         * Diz se ainda há item não cancelado.
         *
         * @return verdadeiro se houver
         */
        public boolean live() {
            return statuses.stream().anyMatch(s -> s != null && !s.isEmpty() && !"cancelado".equals(s));
        }
    }

    /**
     * Pedido com cobrança em aberto.
     *
     * @param id         pedido
     * @param paymentIds pagamentos gerados
     */
    record OpenCharge(long id, List<String> paymentIds) {
    }
}
