package br.com.kntap.raykookie.refunds.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Pausa antes de tentar de novo uma devolução que falhou: 15 min, 1 h, 3 h, 12 h e depois a cada 12 h, até 8
 * tentativas ({@code RETRY_AFTER_MIN}).
 */
public final class RetrySchedule {

    /** Minutos de espera depois de cada tentativa. */
    public static final List<Integer> RETRY_AFTER_MIN = List.of(15, 60, 180, 720, 720, 720, 720, 720);

    /** Tentativas no máximo. */
    public static final int MAX_ATTEMPTS = RETRY_AFTER_MIN.size();

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private RetrySchedule() {
    }

    /**
     * Quanto esperar depois de uma tentativa que falhou.
     *
     * @param attempts tentativas feitas (a partir de 1)
     * @return a espera, ou vazio se desistiu (precisa de ação da dona)
     */
    public static Optional<Duration> after(int attempts) {
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMinutes(RETRY_AFTER_MIN.get(attempts - 1)));
    }
}
