package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.application.ports.OwnerSessionStore;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limpeza diária das sessões vencidas da dona (G16: no Node, elas nunca eram apagadas).
 */
@Component
public class PurgeExpiredSessionsJob implements ScheduledJob {

    /** Log do que foi apagado. */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeExpiredSessionsJob.class);

    /** Sessões. */
    private final OwnerSessionStore sessions;

    /**
     * Cria a tarefa.
     *
     * @param sessions sessões
     */
    public PurgeExpiredSessionsJob(OwnerSessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code identity.purge-expired-sessions}
     */
    @Override
    public String name() {
        return "identity.purge-expired-sessions";
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
     * Apaga as sessões vencidas.
     */
    @Override
    public void run() {
        LOG.info("Sessões vencidas da dona apagadas: {}", sessions.purgeExpired());
    }
}
