package br.com.kntap.raykookie.releases.application.ports;

import java.util.Optional;

/**
 * Último build de cada app já avisado aos aparelhos (tabela {@code release_notices}).
 */
public interface ReleaseNotices {

    /**
     * Último build avisado.
     *
     * @param app {@code client} ou {@code server}
     * @return o build, ou vazio se nunca foi registrado
     */
    Optional<Integer> lastBuild(String app);

    /**
     * Registra o build como avisado.
     *
     * @param app   {@code client} ou {@code server}
     * @param build o build
     */
    void save(String app, int build);
}
