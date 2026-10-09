package br.com.kntap.raykookie.payments.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tentativas de cobrança no cartão (tabela {@code payment_attempts}): no máximo uma em andamento por pedido ou link
 * (G03).
 */
public interface PaymentAttempts {

    /**
     * Registra a tentativa em andamento, antes de chamar o gateway.
     *
     * @param attempt a tentativa
     * @return falso se já existe outra em andamento para o mesmo pedido ou link
     */
    boolean start(Attempt attempt);

    /**
     * Fecha a tentativa.
     *
     * @param id        tentativa
     * @param state     {@code approved}, {@code pending}, {@code rejected} ou {@code error}
     * @param gatewayId pagamento no gateway (nulo se não houve)
     */
    void finish(UUID id, String state, String gatewayId);

    /**
     * Tentativas que ficaram em andamento ou com erro antes de um instante.
     *
     * @param before limite
     * @return as tentativas
     */
    List<Stale> stale(Instant before);

    /**
     * Tentativa nova.
     *
     * @param id          id (também a chave de idempotência)
     * @param shopOrderId pedido da loja (nulo se for link)
     * @param payLinkId   link (nulo se for pedido)
     * @param customerId  cliente (nulo no link)
     * @param clientIp    IP real
     */
    record Attempt(UUID id, Long shopOrderId, Long payLinkId, Long customerId, String clientIp) {
    }

    /**
     * Tentativa sem desfecho.
     *
     * @param id          id
     * @param shopOrderId pedido da loja
     * @param payLinkId   link
     * @param state       estado atual
     */
    record Stale(UUID id, Long shopOrderId, Long payLinkId, String state) {
    }
}
