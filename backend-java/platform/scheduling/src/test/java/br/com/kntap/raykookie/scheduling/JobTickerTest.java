package br.com.kntap.raykookie.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * O relógio de 5 minutos respeita o JOBS_ENABLED.
 */
class JobTickerTest {

    /**
     * Desligado, o ciclo só roda as tarefas que não existem no Node.
     */
    @Test
    @DisplayName("JOBS_ENABLED=false: o ciclo só roda as tarefas que convivem com o Node")
    @SuppressWarnings("unchecked")
    void disabledTickRunsOnlyJobsAlongsideNode() {

        // ── 1. Ciclo desligado.
        JobRunner runner = mock(JobRunner.class);
        assertThat(new JobTicker(runner, false).tick()).isFalse();

        // ── 2. Só as tarefas marcadas passam pelo filtro.
        ArgumentCaptor<Predicate<ScheduledJob>> which = ArgumentCaptor.forClass(Predicate.class);
        verify(runner).runDue(which.capture());
        verify(runner, never()).runDue();
        assertThat(which.getValue().test(job(true))).isTrue();
        assertThat(which.getValue().test(job(false))).isFalse();
    }

    /**
     * Ligado, o ciclo roda todas.
     */
    @Test
    @DisplayName("JOBS_ENABLED=true: o ciclo roda todas as tarefas")
    void enabledTickRunsAll() {
        JobRunner runner = mock(JobRunner.class);
        assertThat(new JobTicker(runner, true).tick()).isTrue();
        verify(runner).runDue();
        verify(runner, never()).runDue(any());
    }

    /**
     * Tarefa de teste.
     *
     * @param alongsideNode se roda junto com o Node
     * @return a tarefa
     */
    private static ScheduledJob job(boolean alongsideNode) {
        return new ScheduledJob() {

            /** {@inheritDoc} */
            @Override
            public String name() {
                return "teste";
            }

            /** {@inheritDoc} */
            @Override
            public JobFrequency frequency() {
                return JobFrequency.EVERY_5_MIN;
            }

            /** {@inheritDoc} */
            @Override
            public boolean runsAlongsideNode() {
                return alongsideNode;
            }

            /** {@inheritDoc} */
            @Override
            public void run() {
                // nada
            }
        };
    }
}
