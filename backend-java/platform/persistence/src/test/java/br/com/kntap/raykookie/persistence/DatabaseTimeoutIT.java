package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Prova os tempos limite do banco (G06, T09): consulta longa é cancelada pelo
 * {@code statement_timeout} de 15 s e, com o banco travado, pedir conexão falha em segundos em vez
 * de pendurar a requisição.
 */
@SpringBootTest
class DatabaseTimeoutIT extends PostgresTestBase {

    /** Pool da aplicação. */
    @Autowired
    DataSource dataSource;

    /** Cliente JDBC da aplicação (sobre o pool). */
    @Autowired
    JdbcClient jdbc;

    /**
     * Consulta de 20 s é cancelada pelo banco em até 16 s.
     */
    @Test
    @DisplayName("consulta longa é cancelada pelo statement_timeout em até 16 s")
    void longQueryIsCancelled() {

        // ── 1. Pede uma consulta que levaria 20 s.
        long start = System.nanoTime();
        assertThatThrownBy(() -> jdbc.sql("SELECT pg_sleep(20)").query().singleValue())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("statement timeout");

        // ── 2. Foi cancelada dentro do limite.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThanOrEqualTo(Duration.ofSeconds(16));
    }

    /**
     * Com o container pausado (banco que não responde), pedir conexão falha em até 4 s; ao despausar,
     * o pool volta a funcionar.
     *
     * @throws InterruptedException se a espera for interrompida
     */
    @Test
    @DisplayName("com o banco travado, obter conexão falha em até 4 s")
    void unresponsiveDatabaseFailsFast() throws InterruptedException {

        // ── 1. Garante conexões no pool e trava o banco.
        assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
        String id = postgres().getContainerId();
        postgres().getDockerClient().pauseContainerCmd(id).exec();
        try {

            // ── 2. Passa da janela em que o Hikari entrega conexão ociosa sem testá-la.
            Thread.sleep(Duration.ofMillis(600));

            // ── 3. Pedir conexão falha rápido.
            long start = System.nanoTime();
            assertThatThrownBy(() -> borrow()).isInstanceOf(SQLException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThanOrEqualTo(Duration.ofSeconds(4));
        } finally {
            postgres().getDockerClient().unpauseContainerCmd(id).exec();
        }

        // ── 4. O banco voltou: o pool atende de novo.
        assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * Pede uma conexão ao pool e a devolve. Não executa nada nela: com o banco travado e sem tempo
     * limite de leitura, uma consulta ficaria esperando para sempre.
     *
     * @throws SQLException se o pool não conseguir entregar uma conexão válida
     */
    private void borrow() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection).isNotNull();
        }
    }
}
