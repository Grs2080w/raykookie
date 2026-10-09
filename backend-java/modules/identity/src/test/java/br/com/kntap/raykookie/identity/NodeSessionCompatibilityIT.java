package br.com.kntap.raykookie.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sessão aberta pelo Node continua valendo no Java depois do corte: sem novo login (Review Focus 2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class NodeSessionCompatibilityIT extends PostgresTestBase {

    /** Token conhecido, como o Node gera (32 bytes em base64url). */
    private static final String TOKEN = "dGVzdGUtZGUtc2Vzc2FvLWRvLW5vZGUtMzItYnl0ZXM";

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Sessão gravada exatamente como o Node grava abre /api/me por cookie e por Bearer.
     */
    @Test
    @DisplayName("sessão gravada pelo Node vale por cookie e por Bearer")
    void nodeSessionIsAccepted() {

        // ── 1. INSERT idêntico ao de backend/lib/auth.ts (sha256 hex do token, 30 dias).
        jdbc.sql("INSERT INTO sessions (user_id, token_hash, expires_at) "
                        + "SELECT id, encode(sha256(convert_to(:token, 'UTF8')), 'hex'), now() + interval '30 days' "
                        + "FROM users WHERE username = 'dona' ON CONFLICT (token_hash) DO NOTHING")
                .param("token", TOKEN)
                .update();

        // ── 2. Pelo cookie do navegador e pelo Bearer do app.
        Response byCookie = RestAssured.given().port(port).cookie("rayka_session", TOKEN).get("/api/me");
        Response byBearer = RestAssured.given().port(port).header("Authorization", "Bearer " + TOKEN).get("/api/me");

        // ── 3. As duas abrem a conta da dona.
        assertThat(byCookie.statusCode()).isEqualTo(200);
        assertThat(byCookie.jsonPath().getString("username")).isEqualTo("dona");
        assertThat(byBearer.statusCode()).isEqualTo(200);
        assertThat(byBearer.jsonPath().getString("username")).isEqualTo("dona");
    }
}
