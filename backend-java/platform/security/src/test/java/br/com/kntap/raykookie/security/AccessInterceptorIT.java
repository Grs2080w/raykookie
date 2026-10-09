package br.com.kntap.raykookie.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Controle de acesso pela rede, com sessões gravadas no Postgres do jeito que o Node grava (T03).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "rayka.shop-owner=dona",
    "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
            + "classpath:db/testdata",
})
class AccessInterceptorIT extends PostgresTestBase {

    /** Mensagem do Node para rota da dona sem sessão. */
    private static final String OWNER_401 = "{\"error\":\"Sessão expirada. Entre novamente.\"}";

    /** Mensagem do Node para rota do cliente sem sessão. */
    private static final String CUSTOMER_401 = "{\"error\":\"Sua sessão expirou. Entre de novo para continuar.\"}";

    /** Porta aleatória do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco da aplicação. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Requisição pela rede.
     *
     * @return a requisição
     */
    private RequestSpecification api() {
        return RestAssured.given().port(port);
    }

    /**
     * Grava uma sessão de conta (dona ou não) como o Node grava: só o sha256 do token.
     *
     * @param username dona da sessão
     * @param validFor validade a partir de agora (negativa = vencida)
     * @return o token
     */
    private String ownerSession(String username, Duration validFor) {
        String token = SessionTokens.newToken();
        jdbc.sql("INSERT INTO sessions (user_id, token_hash, expires_at) "
                        + "SELECT id, :hash, :expires FROM users WHERE username = :username")
                .param("hash", SessionTokens.hash(token))
                .param("expires", OffsetDateTime.now().plus(validFor))
                .param("username", username)
                .update();
        return token;
    }

    /**
     * Grava uma sessão de cliente válida.
     *
     * @return o token
     */
    private String customerSession() {
        String token = SessionTokens.newToken();
        jdbc.sql("INSERT INTO customer_sessions (token_hash, customer_id, expires_at) "
                        + "SELECT :hash, id, now() + interval '30 days' FROM customers LIMIT 1")
                .param("hash", SessionTokens.hash(token))
                .update();
        return token;
    }

    /**
     * Id da conta da dona.
     *
     * @return o id
     */
    private long ownerId() {
        return jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single();
    }

    /**
     * Rota sem @Access: 500, nunca aberta.
     */
    @Test
    @DisplayName("rota sem @Access: 500 (fail-closed)")
    void missingAccessIsServerError() {

        // ── 1. Chama a rota, até com sessão válida da dona.
        Response response = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("dona", Duration.ofDays(1)))
                .get("/t/sem-access");

        // ── 2. Nunca roda.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.asString()).doesNotContain("aberta");
    }

    /**
     * Rota da dona sem sessão: 401 com a mensagem do Node.
     */
    @Test
    @DisplayName("OWNER sem sessão: 401")
    void ownerWithoutSession() {
        Response response = api().get("/t/dona");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.asString()).isEqualTo(OWNER_401);
    }

    /**
     * Sessão válida de uma conta que não é a dona: 401 (G01, G24).
     */
    @Test
    @DisplayName("OWNER com sessão de outra conta: 401")
    void ownerWithAnotherAccount() {
        Response response = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("outra", Duration.ofDays(1)))
                .get("/t/dona");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.asString()).isEqualTo(OWNER_401);
    }

    /**
     * Dona pelo cookie do navegador.
     */
    @Test
    @DisplayName("OWNER com cookie rayka_session válido: 200")
    void ownerWithCookie() {
        Response response = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("dona", Duration.ofDays(1)))
                .get("/t/dona");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getLong("owner")).isEqualTo(ownerId());
    }

    /**
     * Dona pelo Bearer do app.
     */
    @Test
    @DisplayName("OWNER com Authorization: Bearer válido: 200")
    void ownerWithBearer() {
        Response response = api().header("Authorization", "Bearer " + ownerSession("dona", Duration.ofDays(1)))
                .get("/t/dona");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getLong("owner")).isEqualTo(ownerId());
    }

    /**
     * Sessão vencida não vale.
     */
    @Test
    @DisplayName("sessão vencida: 401")
    void expiredSession() {
        Response response = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("dona", Duration.ofMinutes(-1)))
                .get("/t/dona");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * Token de dona numa rota de cliente, e de cliente numa rota da dona: os tokens não se misturam.
     */
    @Test
    @DisplayName("tokens de dona e de cliente não se misturam")
    void tokensDoNotMix() {

        // ── 1. Uma sessão de cada tipo, mandadas como Bearer na rota do outro tipo.
        Response customerRouteWithOwnerToken = api()
                .header("Authorization", "Bearer " + ownerSession("dona", Duration.ofDays(1))).get("/t/cliente");
        Response ownerRouteWithCustomerToken = api()
                .header("Authorization", "Bearer " + customerSession()).get("/t/dona");

        // ── 2. As duas recusadas, cada uma com a mensagem do seu tipo.
        assertThat(customerRouteWithOwnerToken.statusCode()).isEqualTo(401);
        assertThat(customerRouteWithOwnerToken.asString()).isEqualTo(CUSTOMER_401);
        assertThat(ownerRouteWithCustomerToken.statusCode()).isEqualTo(401);
        assertThat(ownerRouteWithCustomerToken.asString()).isEqualTo(OWNER_401);
    }

    /**
     * Cliente com sessão: 200 com o id dele; sem sessão: 401.
     */
    @Test
    @DisplayName("CUSTOMER com cookie rk_customer: 200; sem sessão: 401")
    void customerSessions() {
        assertThat(api().cookie(SessionTokens.CUSTOMER_COOKIE, customerSession()).get("/t/cliente").statusCode())
                .isEqualTo(200);
        assertThat(api().get("/t/cliente").statusCode()).isEqualTo(401);
    }

    /**
     * Rota aberta que mostra mais para a dona.
     */
    @Test
    @DisplayName("OWNER_OR_PUBLIC: aberta, e reconhece a dona quando há sessão dela")
    void ownerOrPublic() {

        // ── 1. Sem sessão, com sessão de outra conta e com sessão da dona.
        Response anonymous = api().get("/t/opcional");
        Response another = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("outra", Duration.ofDays(1)))
                .get("/t/opcional");
        Response owner = api().cookie(SessionTokens.OWNER_COOKIE, ownerSession("dona", Duration.ofDays(1)))
                .get("/t/opcional");

        // ── 2. Todas abrem; só a da dona a reconhece.
        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.jsonPath().getString("owner")).isNull();
        assertThat(another.jsonPath().getString("owner")).isNull();
        assertThat(owner.jsonPath().getLong("owner")).isEqualTo(ownerId());
    }

    /**
     * Rota pública que pede a dona sem tê-la exigido: 401, nunca uma dona inventada.
     */
    @Test
    @DisplayName("requireOwner numa rota PUBLIC sem sessão: 401")
    void requireOwnerOutsideOwnerRoute() {
        assertThat(api().get("/t/publica-pede-dona").statusCode()).isEqualTo(401);
    }
}
