package br.com.kntap.raykookie.scheduling;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * O relógio das tarefas: a cada 5 minutos, no fuso da loja, pede ao {@link JobRunner} que rode o que
 * estiver na hora. Substitui o {@code POST /api/jobs/tick} do Node e o {@code JOBS_SECRET} (G13).
 */
public class JobTicker {

    /** Quem roda as tarefas. */
    private final JobRunner runner;

    /** {@code JOBS_ENABLED}: desligado, o relógio só roda as tarefas que não existem no Node. */
    private final boolean enabled;

    /**
     * Cria o relógio.
     *
     * @param runner  quem roda as tarefas
     * @param enabled se as tarefas estão ligadas
     */
    public JobTicker(JobRunner runner, boolean enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    /**
     * Um ciclo: roda as tarefas que estão na hora. Desligado, roda só as que não existem no Node
     * ({@link ScheduledJob#runsAlongsideNode()}).
     *
     * @return verdadeiro se o ciclo rodou todas (falso com as tarefas desligadas)
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "America/Sao_Paulo")
    public boolean tick() {
        if (!enabled) {
            runner.runDue(ScheduledJob::runsAlongsideNode);
            return false;
        }
        runner.runDue();
        return true;
    }
}
