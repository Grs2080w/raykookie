package br.com.kntap.raykookie.engagement.application;

import br.com.kntap.raykookie.engagement.application.ports.EngagementStore;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * A cada 5 minutos refaz o agregado de hoje e de ontem (o de ontem pega eventos que chegaram depois da meia-noite,
 * de quem estava sem rede), então a gestão vê números com poucos minutos de atraso.
 */
@Component
public class EngagementRollupJob implements ScheduledJob {

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
    public EngagementRollupJob(EngagementStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code engagement.rollup}
     */
    @Override
    public String name() {
        return "engagement.rollup";
    }

    /**
     * A cada ciclo de 5 minutos.
     *
     * @return {@link JobFrequency#EVERY_5_MIN}
     */
    @Override
    public JobFrequency frequency() {
        return JobFrequency.EVERY_5_MIN;
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
     * Refaz ontem e hoje.
     */
    @Override
    public void run() {
        LocalDate today = new StoreClock(clock).today();
        store.rollup(today.minusDays(1));
        store.rollup(today);
    }
}
