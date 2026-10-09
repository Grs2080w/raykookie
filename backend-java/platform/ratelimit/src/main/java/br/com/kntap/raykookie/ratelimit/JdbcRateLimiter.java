package br.com.kntap.raykookie.ratelimit;

import br.com.kntap.raykookie.persistence.Jdbc;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Contadores em janela fixa na tabela {@code rate_limits} (migration V22). Cada tentativa é um único
 * {@code INSERT … ON CONFLICT DO UPDATE … RETURNING}: atômico no banco, então tentativas simultâneas
 * de várias threads ou de várias instâncias nunca passam do limite.
 */
@Component
public class JdbcRateLimiter implements RateLimiter {

    /** Soma uma tentativa na janela atual e devolve o total. */
    private static final String HIT = """
            INSERT INTO rate_limits (key, window_start, hits) VALUES (:key, :window, 1)
            ON CONFLICT (key, window_start) DO UPDATE SET hits = rate_limits.hits + 1
            RETURNING hits""";

    /** Banco. */
    private final JdbcClient jdbc;

    /** Relógio (injetável nos testes). */
    private final Clock clock;

    /**
     * Cria o limitador.
     *
     * @param jdbc  cliente JDBC sobre o pool da aplicação
     * @param clock relógio
     */
    public JdbcRateLimiter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Registra a tentativa e compara o total da janela com o limite.
     *
     * @param limit   o limite
     * @param subject o assunto
     * @return verdadeiro se esta tentativa está dentro do limite
     */
    @Override
    public boolean hit(RateLimit limit, String subject) {
        int hits = jdbc.sql(HIT)
                .param("key", limit.key(subject))
                .param("window", Jdbc.timestamp(windowStart(limit)))
                .query(Integer.class)
                .single();
        return hits <= limit.maxHits();
    }

    /**
     * Lê o total da janela atual sem somar.
     *
     * @param limit   o limite
     * @param subject o assunto
     * @return verdadeiro se ainda há tentativas na janela
     */
    @Override
    public boolean peek(RateLimit limit, String subject) {
        int hits = jdbc.sql("SELECT hits FROM rate_limits WHERE key = :key AND window_start = :window")
                .param("key", limit.key(subject))
                .param("window", Jdbc.timestamp(windowStart(limit)))
                .query(Integer.class)
                .optional()
                .orElse(0);
        return hits < limit.maxHits();
    }

    /**
     * Apaga todas as janelas do assunto.
     *
     * @param limit   o limite
     * @param subject o assunto
     */
    @Override
    public void reset(RateLimit limit, String subject) {
        jdbc.sql("DELETE FROM rate_limits WHERE key = :key").param("key", limit.key(subject)).update();
    }

    /**
     * Apaga as janelas antigas.
     *
     * @param cutoff instante de corte
     * @return quantas foram apagadas
     */
    @Override
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM rate_limits WHERE window_start < :cutoff")
                .param("cutoff", Jdbc.timestamp(cutoff))
                .update();
    }

    /**
     * Início da janela fixa que contém o instante atual (janelas alinhadas à época Unix).
     *
     * @param limit o limite
     * @return o início da janela
     */
    private Instant windowStart(RateLimit limit) {
        long size = limit.window().toMillis();
        long now = clock.millis();
        return Instant.ofEpochMilli(Math.floorDiv(now, size) * size);
    }
}
