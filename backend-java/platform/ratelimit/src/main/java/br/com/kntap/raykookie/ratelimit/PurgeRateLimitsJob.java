package br.com.kntap.raykookie.ratelimit;

import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limpeza diária dos contadores de tentativa (G05): apaga as janelas mais velhas que a maior janela em
 * uso. No Node, o mapa em memória só crescia.
 */
@Component
public class PurgeRateLimitsJob implements ScheduledJob {

    /** Log do que foi apagado. */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeRateLimitsJob.class);

    /** Contadores. */
    private final RateLimiter limiter;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria a tarefa.
     *
     * @param limiter contadores
     * @param clock   relógio
     */
    public PurgeRateLimitsJob(RateLimiter limiter, Clock clock) {
        this.limiter = limiter;
        this.clock = clock;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code platform.purge-rate-limits}
     */
    @Override
    public String name() {
        return "platform.purge-rate-limits";
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
     * Apaga as janelas antigas.
     */
    @Override
    public void run() {
        int purged = limiter.purgeOlderThan(clock.instant().minus(RateLimits.LONGEST_WINDOW));
        LOG.info("Contadores de tentativa antigos apagados: {}", purged);
    }
}
