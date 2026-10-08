package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tarefas agendadas de devoluções: novas tentativas (a cada 5 min), conciliação das que estão processando (a cada
 * 6 h) e limpeza das fotos antigas (diária).
 */
@Configuration
public class RefundJobs {

    /**
     * Refaz as devoluções cuja hora chegou ({@code retryDue}).
     *
     * @param sender envio
     * @return a tarefa
     */
    @Bean
    public ScheduledJob retryDueRefundsJob(RefundSender sender) {
        return new NamedJob("refunds.retry-due", JobFrequency.EVERY_5_MIN, sender::retryDue);
    }

    /**
     * Confere as devoluções que estão processando ({@code reconcilePending}, parte das devoluções).
     *
     * @param reconciliation conciliação
     * @return a tarefa
     */
    @Bean
    public ScheduledJob reconcileProcessingRefundsJob(RefundReconciliation reconciliation) {
        return new NamedJob("refunds.reconcile-pending", JobFrequency.EVERY_6_HOURS,
                reconciliation::reconcileProcessing);
    }

    /**
     * Apaga as fotos de devoluções decididas há mais de 180 dias ({@code cleanupOldRefundPhotos}).
     *
     * @param refunds devoluções
     * @return a tarefa
     */
    @Bean
    public ScheduledJob cleanupOldRefundPhotosJob(Refunds refunds) {
        return new NamedJob("refunds.cleanup-old-photos", JobFrequency.DAILY_AT_4AM, refunds::cleanupOldPhotos);
    }

    /**
     * Tarefa simples: nome, frequência e o trabalho.
     *
     * @param name      nome
     * @param frequency frequência
     * @param work      trabalho
     */
    record NamedJob(String name, JobFrequency frequency, Runnable work) implements ScheduledJob {

        /** {@inheritDoc} */
        @Override
        public void run() {
            work.run();
        }
    }
}
