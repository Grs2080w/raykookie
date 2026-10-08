package br.com.kntap.raykookie.scheduling;

import br.com.kntap.raykookie.persistence.Jdbc;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Registro das execuções em {@code job_runs} (migrations V22 e V23).
 */
@Repository
public class JobRunsRepository {

    /** Tamanho máximo do erro guardado. */
    private static final int MAX_ERROR = 2000;

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JobRunsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Última execução de uma tarefa.
     *
     * @param name nome da tarefa
     * @return a execução, ou {@link JobRun#never()}
     */
    public JobRun find(String name) {
        return jdbc.sql("SELECT last_run_on, last_started_at, last_ok_at, last_error FROM job_runs WHERE name = :n")
                .param("n", name)
                .query((rs, row) -> new JobRun(Jdbc.localDate(rs, "last_run_on"), Jdbc.instant(rs, "last_started_at"),
                        Jdbc.instant(rs, "last_ok_at"), rs.getString("last_error")))
                .optional()
                .orElse(JobRun.never());
    }

    /**
     * Registra o início de uma tentativa.
     *
     * @param name  nome da tarefa
     * @param today dia no fuso da loja
     * @param now   agora
     */
    public void markStarted(String name, LocalDate today, Instant now) {
        jdbc.sql("""
                INSERT INTO job_runs (name, last_run_on, last_started_at) VALUES (:name, :today, :now)
                ON CONFLICT (name) DO UPDATE SET last_run_on = EXCLUDED.last_run_on,
                                                 last_started_at = EXCLUDED.last_started_at""")
                .param("name", name)
                .param("today", today)
                .param("now", Jdbc.timestamp(now))
                .update();
    }

    /**
     * Registra o sucesso da tentativa.
     *
     * @param name nome da tarefa
     * @param now  fim da execução
     */
    public void markOk(String name, Instant now) {
        jdbc.sql("UPDATE job_runs SET last_ok_at = :now, last_error = NULL WHERE name = :name")
                .param("name", name)
                .param("now", Jdbc.timestamp(now))
                .update();
    }

    /**
     * Registra a falha da tentativa.
     *
     * @param name  nome da tarefa
     * @param error o erro
     */
    public void markFailed(String name, Throwable error) {
        String text = String.valueOf(error);
        jdbc.sql("UPDATE job_runs SET last_error = :error WHERE name = :name")
                .param("name", name)
                .param("error", text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text)
                .update();
    }
}
