package br.com.kntap.raykookie.customers.application;

import br.com.kntap.raykookie.customers.application.ports.CustomerSessionStore;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limpeza diária das sessões vencidas de cliente (G16).
 */
@Component
public class PurgeCustomerSessionsJob implements ScheduledJob {

    /** Log do que foi apagado. */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeCustomerSessionsJob.class);

    /** Sessões. */
    private final CustomerSessionStore sessions;

    /**
     * Cria a tarefa.
     *
     * @param sessions sessões
     */
    public PurgeCustomerSessionsJob(CustomerSessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code customers.purge-expired-sessions}
     */
    @Override
    public String name() {
        return "customers.purge-expired-sessions";
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
        LOG.info("Sessões vencidas de cliente apagadas: {}", sessions.purgeExpired());
    }
}
