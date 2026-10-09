package br.com.kntap.raykookie.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Frequências das tarefas agendadas e a regra de "está na hora?" de cada uma (G13). No Node, tudo
 * dependia de o tick cair numa janela de minutos; um servidor parado nessa janela perdia a execução.
 */
public enum JobFrequency {

    /** A cada ciclo de 5 minutos. */
    EVERY_5_MIN,

    /** A cada 6 horas desde a última execução bem-sucedida. */
    EVERY_6_HOURS,

    /** Uma vez por dia, a partir das 4h no fuso da loja; recupera o dia perdido com o servidor parado. */
    DAILY_AT_4AM;

    /** Intervalo mínimo entre duas tentativas de uma tarefa de 5 minutos (folga para o atraso do agendador). */
    static final Duration FIVE_MINUTES_GAP = Duration.ofMinutes(4);

    /** Espera depois de uma tentativa que falhou, nas tarefas de 6 horas. */
    static final Duration SIX_HOURS_RETRY_GAP = Duration.ofHours(1);

    /** Hora local a partir da qual a tarefa diária roda. */
    static final int DAILY_HOUR = 4;

    /**
     * Diz se a tarefa está na hora.
     *
     * @param last       última execução registrada ({@link JobRun#never()} se nunca rodou)
     * @param now        agora
     * @param storeNow   agora no fuso da loja
     * @return verdadeiro se deve rodar
     */
    public boolean isDue(JobRun last, Instant now, ZonedDateTime storeNow) {
        return switch (this) {
            case EVERY_5_MIN -> olderThan(last.lastStartedAt(), now, FIVE_MINUTES_GAP);
            case EVERY_6_HOURS -> olderThan(last.lastOkAt(), now, Duration.ofHours(6))
                    && olderThan(last.lastStartedAt(), now, SIX_HOURS_RETRY_GAP);
            case DAILY_AT_4AM -> storeNow.getHour() >= DAILY_HOUR
                    && (last.lastRunOn() == null || last.lastRunOn().isBefore(storeNow.toLocalDate()));
        };
    }

    /**
     * Diz se um instante é nulo ou mais antigo que um intervalo.
     *
     * @param instant o instante (nulo = nunca)
     * @param now     agora
     * @param gap     o intervalo
     * @return verdadeiro se já passou o intervalo
     */
    private static boolean olderThan(Instant instant, Instant now, Duration gap) {
        return instant == null || !instant.plus(gap).isAfter(now);
    }
}
