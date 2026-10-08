package br.com.kntap.raykookie.identity.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.time.Instant;

/**
 * Porta de saída: sessões da dona ({@code sessions}).
 */
public interface OwnerSessionStore {

    /**
     * Grava uma sessão nova (só o hash do token).
     *
     * @param owner     dona
     * @param tokenHash sha256 do token
     * @param expiresAt vencimento
     */
    void create(OwnerId owner, String tokenHash, Instant expiresAt);

    /**
     * Apaga uma sessão.
     *
     * @param tokenHash sha256 do token
     */
    void delete(String tokenHash);

    /**
     * Apaga as sessões vencidas (G16).
     *
     * @return quantas foram apagadas
     */
    int purgeExpired();
}
