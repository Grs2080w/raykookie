package br.com.kntap.raykookie.customers.application.ports;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.time.Instant;

/**
 * Porta de saída: sessões de cliente ({@code customer_sessions}).
 */
public interface CustomerSessionStore {

    /**
     * Grava uma sessão nova (só o hash do token).
     *
     * @param customer  cliente
     * @param tokenHash sha256 do token
     * @param expiresAt vencimento
     */
    void create(CustomerId customer, String tokenHash, Instant expiresAt);

    /**
     * Apaga uma sessão.
     *
     * @param tokenHash sha256 do token
     */
    void delete(String tokenHash);

    /**
     * Apaga as sessões vencidas (G16).
     *
     * @return quantas
     */
    int purgeExpired();
}
