package br.com.kntap.raykookie.payments.domain;

import br.com.kntap.raykookie.kernel.Money;

/**
 * Pagamento como o Mercado Pago o devolve (sempre consultado direto na API, nunca o corpo de uma notificação).
 *
 * @param id                id do pagamento
 * @param status            {@code approved}, {@code pending}, {@code in_process}, {@code rejected}, {@code refunded}…
 * @param statusDetail      motivo do status (recusa do cartão)
 * @param externalReference id do pedido ou {@code link:<id>}
 * @param amount            valor cobrado
 * @param refundedAmount    valor já devolvido
 * @param qrCode            Pix copia e cola (só no Pix recém-criado)
 * @param qrBase64          QR do Pix em PNG base64
 * @param challengeUrl      endereço do desafio 3DS que o comprador precisa concluir (nulo se não há desafio)
 */
public record GatewayPayment(String id, String status, String statusDetail, String externalReference, Money amount,
        Money refundedAmount, String qrCode, String qrBase64, String challengeUrl) {

    /**
     * Pagamento sem desafio 3DS.
     *
     * @param id                id do pagamento
     * @param status            situação
     * @param statusDetail      motivo do status
     * @param externalReference id do pedido ou {@code link:<id>}
     * @param amount            valor cobrado
     * @param refundedAmount    valor já devolvido
     * @param qrCode            Pix copia e cola
     * @param qrBase64          QR do Pix em PNG base64
     */
    public GatewayPayment(String id, String status, String statusDetail, String externalReference, Money amount,
            Money refundedAmount, String qrCode, String qrBase64) {
        this(id, status, statusDetail, externalReference, amount, refundedAmount, qrCode, qrBase64, null);
    }

    /**
     * Espera o comprador concluir o desafio 3DS no banco.
     *
     * @return {@code true} se há desafio pendente
     */
    public boolean challengePending() {
        return challengeUrl != null && !challengeUrl.isEmpty();
    }

    /**
     * Diz se foi aprovado.
     *
     * @return verdadeiro se {@code approved}
     */
    public boolean approved() {
        return "approved".equals(status);
    }

    /**
     * Diz se ainda está em aberto.
     *
     * @return verdadeiro se {@code pending} ou {@code in_process}
     */
    public boolean open() {
        return "pending".equals(status) || "in_process".equals(status);
    }

    /**
     * Diz se houve devolução ou estorno.
     *
     * @return verdadeiro se {@code refunded}, {@code charged_back} ou com valor devolvido
     */
    public boolean refundedOrChargedBack() {
        return "refunded".equals(status) || "charged_back".equals(status) || refundedAmount.cents() > 0;
    }
}
