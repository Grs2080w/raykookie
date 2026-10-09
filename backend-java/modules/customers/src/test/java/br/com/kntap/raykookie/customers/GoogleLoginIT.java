package br.com.kntap.raykookie.customers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Login com Google: {@code id_token} conferido localmente com a JWKS (servida pelo WireMock, com uma chave
 * de teste) e o fluxo OAuth do site com o Google falso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false", "rayka.site-url=https://loja.teste",
            "rayka.google.client-id=app-da-loja", "rayka.google.client-secret=segredo",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class GoogleLoginIT extends PostgresTestBase {

    /** O Google falso. */
    private static final WireMockServer GOOGLE = new WireMockServer(wireMockConfig().dynamicPort());

    /** Chave de assinatura de teste (a pública vai na JWKS do falso). */
    private static final RSAKey KEY;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("teste").generate();
        } catch (com.nimbusds.jose.JOSEException impossible) {
            throw new IllegalStateException(impossible);
        }
        GOOGLE.start();
        GOOGLE.stubFor(get(urlPathEqualTo("/certs")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody(new JWKSet(KEY.toPublicJWK()).toString())));
    }

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Aponta os endereços do Google para o falso.
     *
     * @param registry propriedades
     */
    @DynamicPropertySource
    static void googleUrls(DynamicPropertyRegistry registry) {
        registry.add("rayka.google.jwks-url", () -> GOOGLE.baseUrl() + "/certs");
        registry.add("rayka.google.token-url", () -> GOOGLE.baseUrl() + "/token");
        registry.add("rayka.google.userinfo-url", () -> GOOGLE.baseUrl() + "/userinfo");
        registry.add("rayka.google.auth-url", () -> GOOGLE.baseUrl() + "/auth");
    }

    /**
     * Para o falso.
     */
    @AfterAll
    static void stop() {
        GOOGLE.stop();
    }

    /**
     * Um id_token assinado, com os campos do Google.
     *
     * @param aud      público
     * @param iss      emissor
     * @param exp      validade
     * @param verified e-mail confirmado
     * @param email    e-mail
     * @param signer   chave que assina
     * @return o token
     * @throws Exception se a assinatura falhar
     */
    private static String idToken(String aud, String iss, Instant exp, boolean verified, String email, RSAKey signer)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().audience(aud).issuer(iss).subject("google-" + email)
                .expirationTime(Date.from(exp)).claim("email", email).claim("email_verified", verified)
                .claim("name", "Nome Google").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("teste").build(), claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    /**
     * Manda o token como o app.
     *
     * @param token o id_token
     * @return a resposta
     */
    private Response appLogin(String token) {
        return RestAssured.given().port(port).header("x-client", "mobile").contentType(ContentType.JSON)
                .body(Map.of("idToken", token)).post("/api/cliente/google/token");
    }

    /**
     * Token válido: cria a conta e abre a sessão; o mesmo e-mail depois cai na mesma conta.
     *
     * @throws Exception se a assinatura falhar
     */
    @Test
    @DisplayName("id_token válido: cria a conta e entra; liga por e-mail a conta existente")
    void validToken() throws Exception {

        // ── 1. Conta nova pelo Google.
        Instant later = Instant.now().plusSeconds(600);
        Response created = appLogin(idToken("app-da-loja", "https://accounts.google.com", later, true,
                "Nova@Teste.com", KEY));
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.jsonPath().getString("token")).hasSize(43);
        assertThat(jdbc.sql("SELECT name FROM customers WHERE email = 'nova@teste.com'").query(String.class).single())
                .isEqualTo("Nome Google");

        // ── 2. Conta existente com senha (ana): liga o Google a ela.
        assertThat(appLogin(idToken("app-da-loja", "accounts.google.com", later, true, "ana@teste.com", KEY))
                .statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT google_sub FROM customers WHERE email = 'ana@teste.com'").query(String.class)
                .single()).isEqualTo("google-ana@teste.com");
    }

    /**
     * Cada recusa com a mensagem do Node.
     *
     * @throws Exception se a assinatura falhar
     */
    @Test
    @DisplayName("id_token recusado: outro app, emissor, vencido, e-mail sem confirmar, assinatura falsa")
    void refusedTokens() throws Exception {

        // ── 1. Tokens com um problema cada.
        Instant later = Instant.now().plusSeconds(600);
        RSAKey stranger = new RSAKeyGenerator(2048).keyID("teste").generate();
        String otherApp = idToken("outro-app", "accounts.google.com", later, true, "x@teste.com", KEY);
        String otherIssuer = idToken("app-da-loja", "https://falso.com", later, true, "x@teste.com", KEY);
        String expired = idToken("app-da-loja", "accounts.google.com", Instant.now().minusSeconds(60), true,
                "x@teste.com", KEY);
        String unverified = idToken("app-da-loja", "accounts.google.com", later, false, "x@teste.com", KEY);
        String forged = idToken("app-da-loja", "accounts.google.com", later, true, "x@teste.com", stranger);

        // ── 2. Cada um com a mensagem do Node.
        assertRefused(appLogin(otherApp), "Identificação do Google emitida para outro aplicativo.");
        assertRefused(appLogin(otherIssuer), "Identificação do Google inválida.");
        assertRefused(appLogin(expired), "A identificação do Google expirou. Tente entrar de novo.");
        assertRefused(appLogin(unverified),
                "O Google não confirmou o seu e-mail. Use outra conta ou entre com e-mail e senha.");
        assertRefused(appLogin(forged), "O Google não reconheceu essa identificação.");
        assertRefused(appLogin("x".repeat(40)), "O Google não reconheceu essa identificação.");
    }

    /**
     * Confere uma recusa.
     *
     * @param response resposta
     * @param message  mensagem esperada
     */
    private static void assertRefused(Response response, String message) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("error")).isEqualTo(message);
    }

    /**
     * Fluxo do site: o state guardado no cookie precisa voltar igual; a troca do código cria a sessão.
     */
    @Test
    @DisplayName("site: state diferente do cookie recusa; state certo entra e vai para o destino")
    void webFlow() {

        // ── 1. Início: redireciona para o Google com o state e guarda o cookie do fluxo.
        Response start = RestAssured.given().port(port).redirects().follow(false)
                .get("/api/cliente/google?next=/cliente/pedidos/7");
        assertThat(start.statusCode()).isEqualTo(307);
        String location = start.getHeader("Location");
        assertThat(location).startsWith(GOOGLE.baseUrl() + "/auth?client_id=app-da-loja&redirect_uri=")
                .contains("scope=openid+email+profile", "prompt=select_account");
        String state = location.replaceAll(".*state=([^&]+).*", "$1");
        String cookie = start.getCookie("rk_oauth");
        assertThat(start.getHeader("Set-Cookie")).contains("Path=/api/cliente/google", "Max-Age=600", "HttpOnly");

        // ── 2. Volta com state diferente: recusa.
        Response wrong = RestAssured.given().port(port).redirects().follow(false).cookie("rk_oauth", cookie)
                .get("/api/cliente/google/callback?code=c&state=outro");
        assertThat(wrong.getHeader("Location")).isEqualTo("https://loja.teste/cliente/entrar?erro=google-invalido");

        // ── 3. Volta com o state certo: troca o código e entra.
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"access_token\":\"tok\"}")));
        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"sub\":\"web-1\",\"email\":\"Web@Teste.com\",\"name\":\"Web\","
                        + "\"email_verified\":true}")));
        Response ok = RestAssured.given().port(port).redirects().follow(false).cookie("rk_oauth", cookie)
                .get("/api/cliente/google/callback?code=c&state=" + state);
        assertThat(ok.getHeader("Location")).isEqualTo("https://loja.teste/cliente/pedidos/7");
        assertThat(ok.getCookie("rk_customer")).hasSize(43);
        assertThat(jdbc.sql("SELECT count(*) FROM customers WHERE google_sub = 'web-1' AND email = 'web@teste.com'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * Cancelado no Google e falha na troca.
     */
    @Test
    @DisplayName("site: cancelado e falha na troca do código")
    void webFailures() {
        assertThat(RestAssured.given().port(port).redirects().follow(false)
                .get("/api/cliente/google/callback?error=access_denied").getHeader("Location"))
                .endsWith("erro=google-cancelado");
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":\"invalid_grant\"}")));
        String cookie = RestAssured.given().port(port).redirects().follow(false).get("/api/cliente/google")
                .getCookie("rk_oauth");
        String state = java.net.URLDecoder.decode(cookie, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll(".*\"state\":\"([^\"]+)\".*", "$1");
        assertThat(RestAssured.given().port(port).redirects().follow(false).cookie("rk_oauth", cookie)
                .get("/api/cliente/google/callback?code=c&state=" + state).getHeader("Location"))
                .endsWith("erro=google-falhou");
    }
}
