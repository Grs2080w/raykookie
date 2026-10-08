package br.com.kntap.raykookie;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Prova que a aplicação sobe com a configuração mínima e responde à verificação de saúde.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationStartsIT {

    /** Porta aleatória escolhida pelo Spring para este teste. */
    @LocalServerPort
    int port;

    /**
     * Sobe o servidor e confere que o actuator responde 200.
     */
    @Test
    @DisplayName("a aplicação sobe e o actuator responde")
    void starts() {

        // ── 1. Chama o endpoint de saúde do actuator pela rede, como um cliente real.
        var response = RestAssured.given().port(port).get("/actuator/health");

        // ── 2. O servidor precisa estar de pé.
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
