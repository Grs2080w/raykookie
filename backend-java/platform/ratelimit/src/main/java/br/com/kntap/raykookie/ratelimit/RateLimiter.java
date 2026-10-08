package br.com.kntap.raykookie.ratelimit;

import java.time.Instant;

/**
 * Contadores de tentativas compartilhados entre instâncias do servidor (G05, G23): o estado fica no
 * Postgres, não na memória do processo.
 */
public interface RateLimiter {

    /**
     * Registra uma tentativa e diz se ela ainda está dentro do limite.
     *
     * @param limit   o limite
     * @param subject o assunto contado (ex.: {@code usuario|ip})
     * @return verdadeiro se esta tentativa está dentro do limite; falso se passou
     */
    boolean hit(RateLimit limit, String subject);

    /**
     * Diz, sem registrar nada, se mais uma tentativa ainda caberia no limite.
     *
     * @param limit   o limite
     * @param subject o assunto
     * @return verdadeiro se ainda há tentativas na janela atual
     */
    boolean peek(RateLimit limit, String subject);

    /**
     * Zera o contador de um assunto (ex.: login certo depois de erros).
     *
     * @param limit   o limite
     * @param subject o assunto
     */
    void reset(RateLimit limit, String subject);

    /**
     * Apaga as janelas que começaram antes de um instante (limpeza diária).
     *
     * @param cutoff instante de corte
     * @return quantas janelas foram apagadas
     */
    int purgeOlderThan(Instant cutoff);
}
