package br.com.kntap.raykookie.payments.application.ports;

/**
 * Entregas de webhook já processadas (tabela {@code webhook_deliveries}, G18).
 */
public interface WebhookDeliveries {

    /**
     * Diz se a entrega já foi processada.
     *
     * @param requestId {@code x-request-id}
     * @return verdadeiro se já foi
     */
    boolean seen(String requestId);

    /**
     * Registra a entrega processada.
     *
     * @param requestId {@code x-request-id}
     * @param dataId    {@code data.id}
     */
    void remember(String requestId, String dataId);

    /**
     * Apaga as entregas antigas.
     *
     * @param days mais antigas que isto
     * @return quantas
     */
    int purgeOlderThanDays(int days);
}
