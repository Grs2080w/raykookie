package br.com.kntap.raykookie.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.testing.ManualClock;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tarefas agendadas com o Postgres real: recuperação do dia perdido, uma instância por vez, falha isolada.
 */
@SpringBootTest(properties = "rayka.jobs-enabled=false")
class JobRunnerIT extends PostgresTestBase {

    /** 04h05 de 2026-10-08 em São Paulo (UTC-3). */
    private static final Instant TODAY_0405 = Instant.parse("2026-10-08T07:05:00Z");

    /** Registro das execuções. */
    @Autowired
    JobRunsRepository runs;

    /** Banco. */
    @Autowired
    DataSource dataSource;

    /** Cliente JDBC. */
    @Autowired
    JdbcClient jdbc;

    /** Relógio controlado. */
    @Autowired
    ManualClock clock;

    /** Alertas. */
    @Autowired
    OpsAlert alert;

    /** Métricas. */
    @Autowired
    SimpleMeterRegistry registry;

    /**
     * Tarefa de teste que conta as execuções e pode demorar ou falhar.
     *
     * @param name      nome
     * @param frequency frequência
     * @param counter   contador de execuções
     * @param pause     quanto demora
     * @param fails     se lança
     */
    record CountingJob(String name, JobFrequency frequency, AtomicInteger counter, Duration pause, boolean fails)
            implements ScheduledJob {

        /**
         * Conta, espera e talvez falhe.
         */
        @Override
        public void run() {
            counter.incrementAndGet();
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (fails) {
                throw new IllegalStateException("falha de teste em " + name);
            }
        }
    }

    /**
     * Tabela limpa e relógio no mesmo instante em cada teste.
     */
    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM job_runs").update();
        clock.set(TODAY_0405);
    }

    /**
     * Executor com as tarefas dadas.
     *
     * @param jobs tarefas
     * @return o executor
     */
    private JobRunner runner(ScheduledJob... jobs) {
        return new JobRunner(List.of(jobs), runs, dataSource, clock, alert);
    }

    /**
     * Tarefa diária que não rodou ontem (servidor parado): roda no primeiro ciclo depois das 4h de hoje, e
     * só uma vez no dia.
     */
    @Test
    @DisplayName("diária perdida roda no primeiro ciclo depois das 4h e não repete no dia")
    void missedDailyJobIsRecovered() {

        // ── 1. Última execução anteontem; o servidor ficou parado ontem.
        AtomicInteger counter = new AtomicInteger();
        CountingJob daily = new CountingJob("teste.diaria", JobFrequency.DAILY_AT_4AM, counter, Duration.ZERO, false);
        runs.markStarted("teste.diaria", LocalDate.parse("2026-10-06"), Instant.parse("2026-10-06T07:00:00Z"));
        JobRunner runner = runner(daily);

        // ── 2. Às 03h50 ainda não; às 04h05 roda; às 04h10 e às 23h não repete.
        clock.set(Instant.parse("2026-10-08T06:50:00Z"));
        Map<String, JobOutcome> before = runner.runDue();
        clock.set(TODAY_0405);
        Map<String, JobOutcome> first = runner.runDue();
        clock.advance(Duration.ofMinutes(5));
        Map<String, JobOutcome> second = runner.runDue();
        clock.set(Instant.parse("2026-10-09T02:00:00Z"));
        Map<String, JobOutcome> lateNight = runner.runDue();

        // ── 3. Exatamente uma execução, registrada no dia de hoje.
        assertThat(before).containsEntry("teste.diaria", JobOutcome.NOT_DUE);
        assertThat(first).containsEntry("teste.diaria", JobOutcome.RAN);
        assertThat(second).containsEntry("teste.diaria", JobOutcome.NOT_DUE);
        assertThat(lateNight).containsEntry("teste.diaria", JobOutcome.NOT_DUE);
        assertThat(counter.get()).isEqualTo(1);
        assertThat(runs.find("teste.diaria").lastRunOn()).isEqualTo(LocalDate.parse("2026-10-08"));
        assertThat(runs.find("teste.diaria").lastOkAt()).isNotNull();
    }

    /**
     * Duas instâncias chamando ao mesmo tempo: a tarefa roda uma vez só.
     *
     * @throws Exception se uma thread falhar
     */
    @Test
    @DisplayName("duas instâncias ao mesmo tempo: a tarefa roda exatamente uma vez")
    void twoInstancesRunTheJobOnce() throws Exception {

        // ── 1. Duas instâncias, cada uma com a sua cópia da tarefa (contador comum), que demora um pouco.
        AtomicInteger counter = new AtomicInteger();
        JobRunner first = runner(new CountingJob("teste.lenta", JobFrequency.EVERY_5_MIN, counter,
                Duration.ofMillis(800), false));
        JobRunner second = runner(new CountingJob("teste.lenta", JobFrequency.EVERY_5_MIN, counter,
                Duration.ofMillis(800), false));
        CountDownLatch start = new CountDownLatch(1);

        // ── 2. As duas disparam juntas.
        List<Future<Map<String, JobOutcome>>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Map<String, JobOutcome>> a = pool.submit(() -> {
                start.await();
                return first.runDue();
            });
            Future<Map<String, JobOutcome>> b = pool.submit(() -> {
                start.await();
                return second.runDue();
            });
            start.countDown();
            results = List.of(a, b);
            for (Future<Map<String, JobOutcome>> result : results) {
                result.get();
            }
        }

        // ── 3. Uma rodou; a outra achou a trava (ou, se chegou depois, viu que já tinha rodado).
        List<JobOutcome> outcomes = List.of(results.get(0).get().get("teste.lenta"),
                results.get(1).get().get("teste.lenta"));
        assertThat(counter.get()).isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(JobOutcome.RAN).doesNotContain(JobOutcome.FAILED);
    }

    /**
     * Tarefa que falha: erro registrado, alerta disparado e a próxima tarefa roda.
     */
    @Test
    @DisplayName("tarefa que lança: last_error preenchido, alerta e a próxima tarefa roda")
    void failureIsIsolated() {

        // ── 1. Uma tarefa que falha antes de outra que funciona.
        AtomicInteger counter = new AtomicInteger();
        double alertsBefore = jobAlerts();
        JobRunner runner = runner(
                new CountingJob("teste.quebrada", JobFrequency.EVERY_5_MIN, counter, Duration.ZERO, true),
                new CountingJob("teste.boa", JobFrequency.EVERY_5_MIN, counter, Duration.ZERO, false));

        // ── 2. Um ciclo.
        Map<String, JobOutcome> outcomes = runner.runDue();

        // ── 3. A quebrada registrou o erro e alertou; a boa rodou.
        assertThat(outcomes).containsEntry("teste.quebrada", JobOutcome.FAILED)
                .containsEntry("teste.boa", JobOutcome.RAN);
        assertThat(runs.find("teste.quebrada").lastError()).contains("falha de teste em teste.quebrada");
        assertThat(runs.find("teste.quebrada").lastOkAt()).isNull();
        assertThat(runs.find("teste.boa").lastError()).isNull();
        assertThat(jobAlerts() - alertsBefore).isEqualTo(1);
    }

    /**
     * Tarefa de 6 horas: espera 6 h depois de dar certo; depois de falhar, tenta de novo em 1 h.
     */
    @Test
    @DisplayName("6 horas: espera depois do sucesso; nova tentativa 1 h depois da falha")
    void everySixHours() {

        // ── 1. Roda agora e tenta de novo 5 h depois.
        AtomicInteger counter = new AtomicInteger();
        JobRunner runner = runner(new CountingJob("teste.6h", JobFrequency.EVERY_6_HOURS, counter, Duration.ZERO,
                false));
        JobOutcome first = runner.runDue().get("teste.6h");
        clock.advance(Duration.ofHours(5));
        JobOutcome fiveHours = runner.runDue().get("teste.6h");
        clock.advance(Duration.ofHours(1));
        JobOutcome sixHours = runner.runDue().get("teste.6h");

        // ── 2. Uma que falha: não repete no mesmo ciclo nem 30 min depois; repete 1 h depois.
        JobRunner failing = runner(new CountingJob("teste.6h-falha", JobFrequency.EVERY_6_HOURS, counter,
                Duration.ZERO, true));
        JobOutcome failed = failing.runDue().get("teste.6h-falha");
        clock.advance(Duration.ofMinutes(30));
        JobOutcome halfHour = failing.runDue().get("teste.6h-falha");
        clock.advance(Duration.ofMinutes(30));
        JobOutcome oneHour = failing.runDue().get("teste.6h-falha");

        // ── 3. Confere a sequência.
        assertThat(List.of(first, fiveHours, sixHours))
                .containsExactly(JobOutcome.RAN, JobOutcome.NOT_DUE, JobOutcome.RAN);
        assertThat(List.of(failed, halfHour, oneHour))
                .containsExactly(JobOutcome.FAILED, JobOutcome.NOT_DUE, JobOutcome.FAILED);
    }

    /**
     * Tarefa de 5 minutos: um ciclo por vez, mesmo se o agendador atrasar um pouco.
     */
    @Test
    @DisplayName("5 minutos: não repete no mesmo ciclo, roda no seguinte")
    void everyFiveMinutes() {

        // ── 1. Dois ciclos seguidos no mesmo minuto e um 5 min depois.
        AtomicInteger counter = new AtomicInteger();
        JobRunner runner = runner(new CountingJob("teste.5min", JobFrequency.EVERY_5_MIN, counter, Duration.ZERO,
                false));
        runner.runDue();
        runner.runDue();
        clock.advance(Duration.ofMinutes(5));
        runner.runDue();

        // ── 2. Duas execuções.
        assertThat(counter.get()).isEqualTo(2);
    }

    /**
     * Nomes repetidos são erro de programação: o servidor não sobe.
     */
    @Test
    @DisplayName("duas tarefas com o mesmo nome: erro")
    void duplicateNames() {
        CountingJob job = new CountingJob("teste.dup", JobFrequency.EVERY_5_MIN, new AtomicInteger(), Duration.ZERO,
                false);
        assertThatThrownBy(() -> runner(job, job)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teste.dup");
    }

    /**
     * Banco fora durante o ciclo: alerta e nenhuma exceção para o agendador.
     */
    @Test
    @DisplayName("banco inacessível: alerta, sem exceção")
    void databaseUnavailable() {

        // ── 1. Executor com um banco que recusa conexão (o registro de execuções continua o real).
        DataSource broken = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nada");
        double alertsBefore = jobAlerts();
        JobRunner runner = new JobRunner(List.of(new CountingJob("teste.sem-banco", JobFrequency.EVERY_5_MIN,
                new AtomicInteger(), Duration.ZERO, false)), runs, broken, clock, alert);

        // ── 2. Falha registrada como desfecho e alerta.
        assertThat(runner.runDue()).containsEntry("teste.sem-banco", JobOutcome.FAILED);
        assertThat(jobAlerts() - alertsBefore).isEqualTo(1);
    }

    /**
     * Alertas de tarefa disparados até agora.
     *
     * @return quantos
     */
    private double jobAlerts() {
        return registry.find("rayka_alerts").tag("kind", "job").counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }
}
