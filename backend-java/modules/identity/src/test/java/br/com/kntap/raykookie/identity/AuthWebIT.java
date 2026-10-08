package br.com.kntap.raykookie.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.identity.application.PurgeExpiredSessionsJob;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rotas da conta da dona pela rede, com o Postgres real e {@code COOKIE_SECURE=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false", "rayka.cookie-secure=true",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class AuthWebIT extends PostgresTestBase {

    /** Senha das contas de teste. */
    private static final String PASSWORD = "senha-de-teste-123";

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Limpeza de sessões. */
    @Autowired
    PurgeExpiredSessionsJob purge;

    /**
     * Sem contadores de tentativa de testes anteriores.
     */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM rate_limits").update();
    }

    /**
     * Requisição pela rede.
     *
     * @return a requisição
     */
    private RequestSpecification api() {
        return RestAssured.given().port(port);
    }

    /**
     * Login com JSON.
     *
     * @param username usuário
     * @param password senha
     * @return a resposta
     */
    private Response login(String username, String password) {
        return api().contentType(ContentType.JSON).body(Map.of("username", username, "password", password))
                .post("/api/auth/login");
    }

    /**
     * Com COOKIE_SECURE=true o cookie sai com Secure; o token não vai no corpo do navegador.
     */
    @Test
    @DisplayName("login: cookie com Secure, HttpOnly, SameSite=Lax e 30 dias")
    void secureCookie() {

        // ── 1. Entra.
        Response response = login("Dona", PASSWORD);

        // ── 2. Cookie com todos os atributos e corpo só com ok.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo("{\"ok\":true}");
        assertThat(response.getHeader("Set-Cookie")).startsWith("rayka_session=").contains("Path=/", "Secure",
                "HttpOnly", "SameSite=Lax", "Max-Age=2592000");
    }

    /**
     * Conta que não é a dona não entra, nem com a senha certa (G24).
     */
    @Test
    @DisplayName("outra conta com a senha certa: 401")
    void otherAccountIsRejected() {
        Response response = login("outra", PASSWORD);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Usuário ou senha incorretos.");
    }

    /**
     * Oito erros barram a nona; o acerto antes disso zera a contagem.
     */
    @Test
    @DisplayName("8 erros barram a 9ª; um acerto antes zera a contagem")
    void rateLimit() {

        // ── 1. Sete erros e um acerto: a contagem zera.
        for (int i = 0; i < 7; i++) {
            login("dona", "errada");
        }
        assertThat(login("dona", PASSWORD).statusCode()).isEqualTo(200);

        // ── 2. Oito erros novos.
        for (int i = 0; i < 8; i++) {
            assertThat(login("dona", "errada").statusCode()).isEqualTo(401);
        }

        // ── 3. A nona é barrada, mesmo com a senha certa.
        Response blocked = login("dona", PASSWORD);
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.jsonPath().getString("error"))
                .isEqualTo("Muitas tentativas. Espere alguns minutos e tente de novo.");
    }

    /**
     * Senha gigante: 400 antes do scrypt; corpo ausente: 401 como credenciais vazias.
     */
    @Test
    @DisplayName("senha > 200: 400; sem corpo: 401")
    void inputLimits() {
        assertThat(login("dona", "x".repeat(201)).statusCode()).isEqualTo(400);
        assertThat(api().contentType(ContentType.JSON).post("/api/auth/login").statusCode()).isEqualTo(401);
    }

    /**
     * Logout apaga a sessão do banco e o cookie.
     */
    @Test
    @DisplayName("logout apaga a sessão do banco e o cookie")
    void logout() {

        // ── 1. Entra como o app.
        String token = api().header("x-client", "mobile").contentType(ContentType.JSON)
                .body(Map.of("username", "dona", "password", PASSWORD)).post("/api/auth/login")
                .jsonPath().getString("token");

        // ── 2. Sai pelo cookie.
        Response out = api().cookie("rayka_session", token).post("/api/auth/logout");

        // ── 3. Sessão some, cookie apagado, token não vale mais.
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(out.getHeader("Set-Cookie")).startsWith("rayka_session=;").contains("Max-Age=0");
        assertThat(api().header("Authorization", "Bearer " + token).get("/api/me").statusCode()).isEqualTo(401);
    }

    /**
     * Cadastro fechado (cadastro desligado e já há contas): 404.
     */
    @Test
    @DisplayName("cadastro fechado: 404 Não encontrado.")
    void registrationClosed() {
        Response response = api().contentType(ContentType.JSON)
                .body(Map.of("username", "dona", "password", "senha-boa-123")).post("/api/auth/register");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Não encontrado.");
    }

    /**
     * A limpeza diária apaga só as sessões vencidas.
     */
    @Test
    @DisplayName("identity.purge-expired-sessions apaga só as sessões vencidas")
    void purgeExpiredSessions() {

        // ── 1. Uma sessão vencida e uma válida.
        jdbc.sql("INSERT INTO sessions (user_id, token_hash, expires_at) SELECT id, 'vencida-teste', "
                + "now() - interval '1 minute' FROM users WHERE username = 'dona'").update();
        jdbc.sql("INSERT INTO sessions (user_id, token_hash, expires_at) SELECT id, 'valida-teste', "
                + "now() + interval '1 day' FROM users WHERE username = 'dona'").update();

        // ── 2. Roda a tarefa.
        purge.run();

        // ── 3. Só a válida sobra.
        assertThat(jdbc.sql("SELECT count(*) FROM sessions WHERE token_hash = 'vencida-teste'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM sessions WHERE token_hash = 'valida-teste'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sessions WHERE expires_at <= now()")
                .query(Integer.class).single()).isZero();
        assertThat(purge.name()).isEqualTo("identity.purge-expired-sessions");
    }
}
