package br.com.kntap.raykookie.scheduling;

import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.observability.OpsAlert;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Roda as tarefas agendadas que estão na hora (G13, G16, G23). Para cada uma: confere no {@code job_runs}
 * se está na hora, pega uma trava no Postgres ({@code pg_try_advisory_lock}) para que só uma instância do
 * servidor a rode, confere de novo já com a trava, roda e registra o resultado. Uma tarefa que falha
 * dispara um alerta e não impede as outras.
 *
 * <p>A trava fica numa conexão separada, segurada só enquanto a tarefa roda; a tarefa em si roda fora de
 * transação, com as transações dela. Se o servidor cair no meio, o Postgres solta a trava sozinho.
 */
public class JobRunner {

    /** Log das execuções. */
    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);

    /** Tarefas registradas, na ordem em que rodam. */
    private final List<ScheduledJob> jobs;

    /** Registro das execuções. */
    private final JobRunsRepository runs;

    /** Banco, para a conexão da trava. */
    private final DataSource dataSource;

    /** Relógio. */
    private final Clock clock;

    /** Alertas. */
    private final OpsAlert alert;

    /**
     * Cria o executor.
     *
     * @param jobs       tarefas registradas pelos módulos
     * @param runs       registro das execuções
     * @param dataSource banco
     * @param clock      relógio
     * @param alert      alertas
     * @throws IllegalStateException se duas tarefas tiverem o mesmo nome
     */
    public JobRunner(List<ScheduledJob> jobs, JobRunsRepository runs, DataSource dataSource, Clock clock,
            OpsAlert alert) {

        // ── 1. O nome é a chave da trava e do registro: dois iguais se atropelariam.
        Set<String> names = new HashSet<>();
        for (ScheduledJob job : jobs) {
            if (!names.add(job.name())) {
                throw new IllegalStateException("Duas tarefas agendadas com o mesmo nome: " + job.name());
            }
        }

        // ── 2. Guarda as dependências.
        this.jobs = List.copyOf(jobs);
        this.runs = runs;
        this.dataSource = dataSource;
        this.clock = clock;
        this.alert = alert;
    }

    /**
     * Roda, uma após a outra, as tarefas que estão na hora.
     *
     * @return o desfecho de cada tarefa, pelo nome
     */
    public Map<String, JobOutcome> runDue() {
        return runDue(job -> true);
    }

    /**
     * Roda, uma após a outra, as tarefas escolhidas que estão na hora.
     *
     * @param which quais tarefas podem rodar
     * @return o desfecho de cada tarefa escolhida, pelo nome
     */
    public Map<String, JobOutcome> runDue(Predicate<ScheduledJob> which) {
        Map<String, JobOutcome> outcomes = new LinkedHashMap<>();
        for (ScheduledJob job : jobs) {
            if (which.test(job)) {
                outcomes.put(job.name(), runIfDue(job));
            }
        }
        return outcomes;
    }

    /**
     * Roda uma tarefa se estiver na hora e se nenhuma outra instância a estiver rodando.
     *
     * @param job a tarefa
     * @return o desfecho
     */
    private JobOutcome runIfDue(ScheduledJob job) {

        // ── 1. Conferência barata, sem trava: quase sempre a tarefa não está na hora.
        if (!isDue(job)) {
            return JobOutcome.NOT_DUE;
        }

        // ── 2. Trava numa conexão própria; quem não pega a trava deixa para a outra instância.
        try (Connection lock = dataSource.getConnection()) {
            if (!advisoryLock(lock, "pg_try_advisory_lock", job)) {
                return JobOutcome.LOCKED_ELSEWHERE;
            }
            try {
                return runLocked(job);
            } finally {
                advisoryLock(lock, "pg_advisory_unlock", job);
            }
        } catch (SQLException | RuntimeException error) {

            // ── 3. Falha do próprio controle (banco fora, por exemplo): alerta e segue para a próxima.
            alert.jobFailed(job.name(), error);
            return JobOutcome.FAILED;
        }
    }

    /**
     * Com a trava: confere de novo (outra instância pode ter acabado de rodar), roda e registra.
     *
     * @param job a tarefa
     * @return o desfecho
     */
    private JobOutcome runLocked(ScheduledJob job) {

        // ── 1. Ainda na hora? A outra instância pode ter rodado entre a conferência e a trava.
        if (!isDue(job)) {
            return JobOutcome.NOT_DUE;
        }

        // ── 2. Marca o início (o dia conta como "tentado", mesmo se falhar).
        Instant start = clock.instant();
        runs.markStarted(job.name(), start.atZone(StoreClock.ZONE).toLocalDate(), start);

        // ── 3. Roda; falha vira registro e alerta, nunca derruba as outras tarefas.
        try {
            job.run();
            runs.markOk(job.name(), clock.instant());
            LOG.info("Tarefa agendada concluída: {}", job.name());
            return JobOutcome.RAN;
        } catch (RuntimeException error) {
            runs.markFailed(job.name(), error);
            alert.jobFailed(job.name(), error);
            return JobOutcome.FAILED;
        }
    }

    /**
     * Diz se a tarefa está na hora, pelo registro no banco.
     *
     * @param job a tarefa
     * @return verdadeiro se deve rodar
     */
    private boolean isDue(ScheduledJob job) {
        Instant now = clock.instant();
        ZonedDateTime storeNow = now.atZone(StoreClock.ZONE);
        return job.frequency().isDue(runs.find(job.name()), now, storeNow);
    }

    /**
     * Pega ou solta a trava da tarefa ({@code hashtext('job:' || nome)}).
     *
     * @param connection conexão da trava
     * @param function   {@code pg_try_advisory_lock} ou {@code pg_advisory_unlock}
     * @param job        a tarefa
     * @return o resultado da função (verdadeiro = trava pega ou solta)
     * @throws SQLException se o banco falhar
     */
    private static boolean advisoryLock(Connection connection, String function, ScheduledJob job)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(hashtext(?))")) {
            statement.setString(1, "job:" + job.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
