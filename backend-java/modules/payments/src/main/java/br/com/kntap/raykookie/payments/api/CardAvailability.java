package br.com.kntap.raykookie.payments.api;

/**
 * Se o pagamento com cartão está ligado agora (configuração pedida e credenciais aceitas pelo Mercado Pago na
 * subida). O app implementa.
 */
public interface CardAvailability {

    /**
     * Diz se o cartão pode ser cobrado.
     *
     * @return verdadeiro se estiver ligado
     */
    boolean enabled();
}
