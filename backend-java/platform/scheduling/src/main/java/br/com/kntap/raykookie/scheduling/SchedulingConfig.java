package br.com.kntap.raykookie.scheduling;

import br.com.kntap.raykookie.observability.OpsAlert;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga as tarefas agendadas: o executor com todas as {@link ScheduledJob} registradas e o relógio de 5
 * minutos ({@code JOBS_ENABLED} desliga).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

    /**
     * O executor das tarefas.
     *
     * @param jobs       tarefas registradas pelos módulos (pode não haver nenhuma)
     * @param runs       registro das execuções
     * @param dataSource banco
     * @param clock      relógio
     * @param alert      alertas
     * @return o executor
     */
    @Bean
    public JobRunner jobRunner(ObjectProvider<ScheduledJob> jobs, JobRunsRepository runs, DataSource dataSource,
            Clock clock, OpsAlert alert) {
        return new JobRunner(jobs.orderedStream().toList(), runs, dataSource, clock, alert);
    }

    /**
     * O relógio de 5 minutos.
     *
     * @param runner  executor
     * @param enabled {@code rayka.jobs-enabled} ({@code JOBS_ENABLED})
     * @return o relógio
     */
    @Bean
    public JobTicker jobTicker(JobRunner runner, @Value("${rayka.jobs-enabled:true}") boolean enabled) {
        return new JobTicker(runner, enabled);
    }
}
