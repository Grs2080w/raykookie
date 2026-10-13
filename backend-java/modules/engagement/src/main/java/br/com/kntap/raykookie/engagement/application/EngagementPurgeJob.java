package br.com.kntap.raykookie.engagement.application;

import br.com.kntap.raykookie.engagement.application.ports.EngagementStore;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Todo dia às 4h apaga os eventos brutos com mais de 90 dias e o agregado diário com mais de 13 meses.
 */
@Component
public class EngagementPurgeJob implements ScheduledJob {

    /** Quanto tempo o evento bruto fica. */
    static final Duration RETENTION = Duration.ofDays(90);

    /** Quanto tempo o agregado diário fica (13 meses). */
    static final int DAILY_RETENTION_DAYS = 400;

    /** Log do que foi apagado. */
    private static final Logger LOG = LoggerFactory.getLogger(EngagementPurgeJob.class);

    /** Eventos e agregado. */
    private final EngagementStore store;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria a tarefa.
     *
     * @param store eventos e agregado
     * @param clock relógio
     */
    public EngagementPurgeJob(EngagementStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code engagement.purge}
     */
    @Override
    public String name() {
        return "engagement.purge";
    }

    /**
     * Uma vez por dia, às 4h.
     *
     * @return {@link JobFrequency#DAILY_AT_4AM}
     */
    @Override
    public JobFrequency frequency() {
        return JobFrequency.DAILY_AT_4AM;
    }

    /**
     * Roda mesmo com as tarefas do Node ligadas: a tabela é nova e o Node não a conhece.
     *
     * @return verdadeiro
     */
    @Override
    public boolean runsAlongsideNode() {
        return true;
    }

    /**
     * Apaga os eventos antigos.
     */
    @Override
    public void run() {
        int purged = store.purgeEventsBefore(clock.instant().minus(RETENTION));
        int daily = store.purgeDailyBefore(new StoreClock(clock).today().minusDays(DAILY_RETENTION_DAYS));
        LOG.info("Engajamento: {} eventos e {} linhas diárias antigas apagados", purged, daily);
    }
}
