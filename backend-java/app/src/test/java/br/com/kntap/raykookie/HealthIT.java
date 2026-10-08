package br.com.kntap.raykookie;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code GET /api/health} e o fim do {@code /api/jobs/tick} (Tarefa 6.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthIT extends PostgresTestBase {

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /**
     * Banco no ar: 200 {"ok":true}.
     */
    @Test
    @DisplayName("banco no ar: 200 {\"ok\":true}")
    void healthy() {
        Response response = RestAssured.given().port(port).get("/api/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("{\"ok\":true}");
    }

    /**
     * Banco travado (container pausado): 503 em até 3 s, e volta ao normal quando o banco volta (T09).
     */
    @Test
    @DisplayName("banco pausado: 503 {\"ok\":false} em até 3 s")
    void databasePaused() {

        // ── 1. Pausa o Postgres e mede a resposta.
        String container = postgres().getContainerId();
        postgres().getDockerClient().pauseContainerCmd(container).exec();
        long start = System.nanoTime();
        Response response;
        try {
            response = RestAssured.given().port(port).get("/api/health");
        } finally {
            postgres().getDockerClient().unpauseContainerCmd(container).exec();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // ── 2. 503 rápido, no formato do Node.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.asString()).isEqualTo("{\"ok\":false}");
        assertThat(elapsedMillis).isLessThanOrEqualTo(3000);
    }

    /**
     * O gatilho externo antigo responde 410.
     */
    @Test
    @DisplayName("POST /api/jobs/tick: 410")
    void jobsTickIsGone() {
        Response response = RestAssured.given().port(port).post("/api/jobs/tick");
        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Tarefas agendadas rodam dentro do servidor.");
    }
}
