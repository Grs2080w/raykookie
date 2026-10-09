package br.com.kntap.raykookie.scheduling;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Última execução registrada de uma tarefa (linha de {@code job_runs}).
 *
 * @param lastRunOn     dia (no fuso da loja) da última tentativa
 * @param lastStartedAt início da última tentativa
 * @param lastOkAt      fim da última execução bem-sucedida
 * @param lastError     erro da última tentativa, ou nulo se ela deu certo
 */
public record JobRun(LocalDate lastRunOn, Instant lastStartedAt, Instant lastOkAt, String lastError) {

    /**
     * Tarefa que nunca rodou.
     *
     * @return a execução vazia
     */
    public static JobRun never() {
        return new JobRun(null, null, null, null);
    }
}
