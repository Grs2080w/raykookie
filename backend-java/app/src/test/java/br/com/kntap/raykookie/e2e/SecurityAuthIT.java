package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segurança, categoria 3: autenticação, sessão e autorização das rotas de gestão.
 */
class SecurityAuthIT extends AppItSupport {

    /**
     * Rotas de gestão (dados de estoque, receitas e dinheiro) que só a dona pode ler. {@code /api/downloads} fica de
     * fora de propósito: é pública (lista só o app do cliente) e o APK de gestão é coberto em {@code AbuseIT}.
     */
    static final List<String> MANAGEMENT_ROUTES = List.of("/api/ingredients", "/api/recipes", "/api/coupons",
            "/api/devolucoes", "/api/categories", "/api/engagement/overview", "/api/me");

    /**
     * Gestão sem sessão, com token inventado, expirado, de cliente e na URL: sempre 401/403, nunca dados.
     */
    @Test
    @DisplayName("gestão: sem token, token inválido, expirado, de cliente ou na query string não passam")
    void managementRequiresAValidOwnerSession() {
        String expired = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO sessions (user_id, token_hash, expires_at)
                SELECT id, :hash, now() - interval '1 minute' FROM users WHERE username = :u""")
                .param("hash", br.com.kntap.raykookie.security.SessionTokens.hash(expired)).param("u", OWNER).update();
        String customer = newCustomer().token();

        for (String path : MANAGEMENT_ROUTES) {
            assertThat(api().get(path).statusCode()).as("%s sem token", path).isEqualTo(401);
            assertThat(as("token-inventado").get(path).statusCode()).as("%s token inválido", path).isEqualTo(401);
            assertThat(as("' OR '1'='1").get(path).statusCode()).as("%s token SQL", path).isEqualTo(401);
            assertThat(as(expired).get(path).statusCode()).as("%s token expirado", path).isEqualTo(401);
            assertThat(as(customer).get(path).statusCode()).as("%s token de cliente", path).isIn(401, 403);
            assertThat(api().get(path + (path.contains("?") ? "&" : "?") + "token=" + panelSession(OWNER))
                    .statusCode()).as("%s token na URL", path).isEqualTo(401);
            assertThat(api().header("Authorization", "Bearer").get(path).statusCode()).as("%s Bearer vazio", path)
                    .isEqualTo(401);
        }
    }

    /**
     * Rotas do cliente não aceitam a sessão da dona e vice-versa.
     */
    @Test
    @DisplayName("sessões não se misturam: token da dona não abre rota do cliente")
    void sessionsAreNotInterchangeable() {
        String owner = panelSession(OWNER);
        assertThat(as(owner).get("/api/cliente/pedidos").statusCode()).isIn(401, 403);
        assertThat(as(owner).get("/api/cliente/me").statusCode()).isIn(401, 403);
    }

    /**
     * Sair invalida o token de verdade, não só o cookie.
     */
    @Test
    @DisplayName("logout: o mesmo token deixa de valer no servidor")
    void logoutInvalidatesTheToken() {
        String token = login(PASSWORD, "198.51.100.10").jsonPath().getString("token");
        assertThat(token).isNotBlank();
        assertThat(as(token).get("/api/me").statusCode()).isEqualTo(200);
        assertThat(as(token).post("/api/auth/logout").statusCode()).isEqualTo(200);
        assertThat(as(token).get("/api/me").statusCode()).isEqualTo(401);
    }

    /**
     * A mensagem não diz se o usuário existe.
     */
    @Test
    @DisplayName("enumeração: usuário inexistente e senha errada respondem igual (status e mensagem)")
    void loginDoesNotRevealWhichAccountsExist() {
        Response wrongPassword = api().header("x-client", "mobile").header("X-Forwarded-For", "198.51.100.20")
                .body(Map.of("username", OWNER, "password", "errada-errada")).post("/api/auth/login");
        Response noSuchUser = api().header("x-client", "mobile").header("X-Forwarded-For", "198.51.100.21")
                .body(Map.of("username", "ninguem-" + UUID.randomUUID(), "password", "errada-errada"))
                .post("/api/auth/login");
        assertThat(noSuchUser.statusCode()).isEqualTo(wrongPassword.statusCode());
        assertThat(noSuchUser.jsonPath().getString("error")).isEqualTo(wrongPassword.jsonPath().getString("error"));

        Response customerWrong = api().header("X-Forwarded-For", "198.51.100.22")
                .body(Map.of("email", "e2e-nao-existe@teste.com", "password", "errada-errada"))
                .post("/api/cliente/login");
        Response customerOther = api().header("X-Forwarded-For", "198.51.100.23")
                .body(Map.of("email", "outro-nao-existe@teste.com", "password", "errada-errada"))
                .post("/api/cliente/login");
        assertThat(customerOther.statusCode()).isEqualTo(customerWrong.statusCode());
        assertThat(customerOther.asString()).isEqualTo(customerWrong.asString());
    }

    /**
     * Força bruta no login da dona: bloqueia mesmo com a senha certa depois de muitas falhas.
     */
    @Test
    @DisplayName("força bruta: depois de 8 senhas erradas o login da dona devolve 429 (mesmo com a senha certa)")
    void bruteForceIsThrottled() {
        for (int i = 0; i < 8; i++) {
            assertThat(login("errada-" + i, "198.51.100.30").statusCode()).isEqualTo(401);
        }
        assertThat(login(PASSWORD, "198.51.100.30").statusCode()).isEqualTo(429);
    }

    /**
     * O cookie de sessão do navegador não é lido por script, não vai a outro site e é "seguro" em HTTPS.
     */
    @Test
    @DisplayName("cookie: HttpOnly, SameSite e Secure (atrás de HTTPS) no login pelo navegador")
    void sessionCookieFlags() {
        Response response = api().header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "198.51.100.40")
                .body(Map.of("username", OWNER, "password", PASSWORD)).post("/api/auth/login");
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().getValues("Set-Cookie").stream().findFirst().orElse("");
        assertThat(cookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=").containsIgnoringCase(
                "Secure");
        assertThat(response.asString()).as("navegador não recebe o token no corpo").doesNotContain("\"token\"");
    }

    /**
     * Redirecionamento do login Google só para caminhos do próprio site.
     */
    @Test
    @DisplayName("open redirect: next do login Google com URL externa é descartado")
    void googleNextCannotLeaveTheSite() {
        for (String next : List.of("https://evil.example/x", "//evil.example", "/\\evil.example", "javascript:alert(1)",
                "http://evil.example")) {
            Response response = api().queryParam("next", next).get("/api/cliente/google");
            String location = response.header("Location");
            if (location != null) {
                assertThat(location).as("next=%s", next).doesNotContain("evil.example").doesNotContain("javascript:");
            }
        }
    }

    /**
     * Caminhos com {@code ..} nunca saem da pasta de arquivos.
     */
    @Test
    @DisplayName("path traversal: documentos legais, downloads e fotos recusam ../")
    void pathTraversalIsRefused() {
        String owner = panelSession(OWNER);
        for (String evil : List.of("..%2F..%2Fetc%2Fpasswd", "%2e%2e%2f%2e%2e%2fetc%2fpasswd",
                "....%2F%2Fetc%2Fpasswd", "..%5C..%5Cwindows%5Cwin.ini")) {
            for (String path : List.of("/api/legal/" + evil, "/api/downloads/" + evil, "/api/fotos/" + evil,
                    "/api/story/produto/" + evil)) {
                Response response = as(owner).get(path);
                assertThat(response.statusCode()).as(path).isIn(400, 404);
                assertThat(response.asString()).as(path).doesNotContain("root:x:", "[extensions]");
            }
        }
    }
}
