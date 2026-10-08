package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prova que um método {@code @Transactional} que falha não deixa nada gravado e não devolve ao pool
 * uma conexão estragada: a próxima conexão funciona normalmente (G07).
 */
@SpringBootTest
@Import(TransactionRollbackIT.JobWriter.class)
class TransactionRollbackIT extends PostgresTestBase {

    /** Quantas conexões o pool tem no máximo (spring.datasource.hikari.maximum-pool-size). */
    private static final int POOL_SIZE = 10;

    /** Serviço transacional de exemplo. */
    @Autowired
    JobWriter writer;

    /** Cliente JDBC da aplicação (sobre o pool Hikari). */
    @Autowired
    JdbcClient jdbc;

    /**
     * Serviço de exemplo que grava em {@code job_runs} e falha no meio da transação.
     */
    static class JobWriter {

        /** Cliente JDBC da aplicação. */
        private final JdbcClient jdbc;

        /**
         * Recebe o cliente JDBC da aplicação.
         *
         * @param jdbc cliente JDBC
         */
        JobWriter(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        /**
         * Grava uma linha e lança exceção da aplicação antes do fim da transação.
         *
         * @param name nome da tarefa gravada
         */
        @Transactional
        public void insertThenFail(String name) {
            insert(name);
            throw new IllegalStateException("falha depois de gravar");
        }

        /**
         * Grava uma linha e depois provoca erro no próprio banco (transação abortada no Postgres).
         *
         * @param name nome da tarefa gravada
         */
        @Transactional
        public void insertThenBreakTheTransaction(String name) {
            insert(name);
            jdbc.sql("SELECT 1 / 0").query().singleValue();
        }

        /**
         * Grava uma linha e confirma.
         *
         * @param name nome da tarefa gravada
         */
        @Transactional
        public void insert(String name) {
            jdbc.sql("INSERT INTO job_runs(name, last_run_on) VALUES (:name, CURRENT_DATE)")
                    .param("name", name)
                    .update();
        }
    }

    /**
     * Começa cada teste com a tabela vazia.
     */
    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM job_runs").update();
    }

    /**
     * Exceção da aplicação: nada gravado e o pool continua saudável.
     */
    @Test
    @DisplayName("exceção dentro de @Transactional desfaz a gravação e o pool continua usável")
    void applicationExceptionRollsBack() {
        assertThatThrownBy(() -> writer.insertThenFail("a")).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
        assertPoolIsHealthy();
    }

    /**
     * Erro do banco no meio da transação: nada gravado e nenhuma conexão volta "abortada" ao pool.
     */
    @Test
    @DisplayName("erro do banco dentro de @Transactional desfaz tudo e a próxima conexão funciona")
    void databaseErrorRollsBack() {
        assertThatThrownBy(() -> writer.insertThenBreakTheTransaction("b")).isInstanceOf(DataAccessException.class);

        assertThat(count()).isZero();
        assertPoolIsHealthy();
    }

    /**
     * Usa todas as conexões do pool (e mais uma) e confere que cada uma responde, mantém os
     * ajustes de sessão e grava normalmente.
     */
    private void assertPoolIsHealthy() {

        // ── 1. Cada conexão responde e mantém o tempo limite e o fuso da sessão.
        for (int i = 0; i <= POOL_SIZE; i++) {
            assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("15s");
            assertThat(jdbc.sql("SHOW timezone").query(String.class).single()).isEqualTo("America/Sao_Paulo");
        }

        // ── 2. Uma transação nova grava e confirma.
        writer.insert("depois");
        assertThat(count()).isEqualTo(1);
    }

    /**
     * Quantas linhas há em {@code job_runs}.
     *
     * @return a contagem
     */
    private int count() {
        return jdbc.sql("SELECT count(*) FROM job_runs").query(Integer.class).single();
    }
}
