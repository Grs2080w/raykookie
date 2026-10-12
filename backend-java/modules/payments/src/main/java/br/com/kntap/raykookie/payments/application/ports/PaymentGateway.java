package br.com.kntap.raykookie.payments.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.time.Instant;
import java.util.List;

/**
 * O gateway de pagamento (Mercado Pago). Toda falha vira {@link br.com.kntap.raykookie.payments.api.GatewayException}.
 */
public interface PaymentGateway {

    /**
     * Cria a cobrança Pix.
     *
     * @param charge dados da cobrança
     * @return o pagamento, com o copia e cola
     */
    GatewayPayment createPix(PixCharge charge);

    /**
     * Cobra o cartão (aprova ou recusa na mesma chamada).
     *
     * @param charge dados da cobrança
     * @return o pagamento
     */
    GatewayPayment createCard(CardCharge charge);

    /**
     * Consulta um pagamento.
     *
     * @param id pagamento
     * @return o pagamento
     */
    GatewayPayment getPayment(String id);

    /**
     * Cancela um pagamento pendente.
     *
     * @param id pagamento
     */
    void cancel(String id);

    /**
     * Devolve um pagamento.
     *
     * @param paymentId      pagamento
     * @param amountOrNull   valor (nulo = tudo)
     * @param idempotencyKey chave de idempotência
     * @return a devolução
     */
    GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey);

    /**
     * Devoluções de um pagamento.
     *
     * @param paymentId pagamento
     * @return as devoluções
     */
    List<GatewayRefund> listRefunds(String paymentId);

    /**
     * Pagamentos de uma referência, mais novos primeiro.
     *
     * @param externalReference {@code external_reference}
     * @return os pagamentos
     */
    List<GatewayPayment> search(String externalReference);

    /**
     * Cobrança Pix.
     *
     * @param reference      {@code external_reference} (id do pedido ou {@code link:<id>})
     * @param idempotencyKey chave de idempotência
     * @param amount         valor
     * @param description    descrição
     * @param email          e-mail do pagador
     * @param firstName      primeiro nome
     * @param expiresAt      validade
     */
    record PixCharge(String reference, String idempotencyKey, Money amount, String description, String email,
            String firstName, Instant expiresAt) {
    }

    /**
     * Cobrança no cartão (o número nunca passa por aqui: só o token).
     *
     * @param reference      {@code external_reference}
     * @param idempotencyKey chave de idempotência
     * @param amount         valor
     * @param description    descrição
     * @param token          token do cartão
     * @param installments   parcelas
     * @param methodId       bandeira
     * @param issuerId       emissor (nulo = não informado)
     * @param email          e-mail do pagador
     * @param firstName      primeiro nome
     * @param cpf            CPF só com dígitos
     * @param buyer          dados do comprador para o antifraude do Mercado Pago
     * @param typeId         {@code credit_card}, {@code debit_card} ou {@code prepaid_card} (nulo = deduzir da
     *                       bandeira: {@code deb…} é débito)
     */
    record CardCharge(String reference, String idempotencyKey, Money amount, String description, String token,
            int installments, String methodId, String issuerId, String email, String firstName, String cpf,
            Buyer buyer, String typeId) {

        /**
         * Cobrança sem o tipo do cartão informado.
         *
         * @param reference      {@code external_reference}
         * @param idempotencyKey chave de idempotência
         * @param amount         valor
         * @param description    descrição
         * @param token          token do cartão
         * @param installments   parcelas
         * @param methodId       bandeira
         * @param issuerId       emissor
         * @param email          e-mail do pagador
         * @param firstName      primeiro nome
         * @param cpf            CPF só com dígitos
         * @param buyer          dados do comprador
         */
        public CardCharge(String reference, String idempotencyKey, Money amount, String description, String token,
                int installments, String methodId, String issuerId, String email, String firstName, String cpf,
                Buyer buyer) {
            this(reference, idempotencyKey, amount, description, token, installments, methodId, issuerId, email,
                    firstName, cpf, buyer, null);
        }
    }

    /**
     * O que o antifraude do Mercado Pago usa para reconhecer o comprador (sem isso ele recusa mais, com
     * {@code cc_rejected_high_risk}). Qualquer campo pode ser nulo.
     *
     * @param lastName sobrenome
     * @param phone    telefone com DDD, só dígitos
     * @param deviceId device ID do script de segurança do Mercado Pago (header {@code X-meli-session-id})
     * @param ip       IP real do cliente
     */
    record Buyer(String lastName, String phone, String deviceId, String ip) {
    }
}
