package br.com.kntap.raykookie.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * Um limite de tentativas: no máximo {@code maxHits} por janela fixa de {@code window}, contado por
 * assunto (usuário + IP, e-mail + IP, cliente, IP…).
 *
 * @param name    nome do limite (parte da chave no banco; único entre os limites)
 * @param maxHits tentativas permitidas por janela
 * @param window  duração da janela
 */
public record RateLimit(String name, int maxHits, Duration window) {

    /**
     * Confere o limite.
     *
     * @param name    nome do limite
     * @param maxHits tentativas permitidas por janela
     * @param window  duração da janela
     */
    public RateLimit {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(window, "window");
        if (name.isBlank() || name.contains("|")) {
            throw new IllegalArgumentException("Nome de limite inválido: " + name);
        }
        if (maxHits < 1 || window.toMillis() < 1000) {
            throw new IllegalArgumentException("Limite inválido: " + name);
        }
    }

    /**
     * Chave do contador no banco para um assunto.
     *
     * @param subject o assunto (ex.: {@code usuario|ip})
     * @return {@code nome|assunto}
     */
    public String key(String subject) {
        return name + "|" + subject;
    }
}
