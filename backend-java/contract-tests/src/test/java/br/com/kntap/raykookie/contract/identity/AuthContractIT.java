package br.com.kntap.raykookie.contract.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conta da dona: login, logout, cadastro e {@code /api/me} (Tarefa 4.1).
 */
class AuthContractIT extends ContractTestBase {

    /** Mensagem de login recusado. */
    private static final String WRONG_LOGIN = "Usuário ou senha incorretos.";

    /** Mensagem do limite de tentativas. */
    private static final String TOO_MANY = "Muitas tentativas. Espere alguns minutos e tente de novo.";

    /** Mensagem de rota da dona sem sessão. */
    private static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /**
     * A dona existe com a senha conhecida antes de cada teste.
     */
    @BeforeEach
    void ownerExists() {
        seed.owner(target.shopOwner());
    }

    /**
     * Login pelo navegador.
     *
     * @param username usuário
     * @param password senha
     * @return a resposta
     */
    private static Response login(String username, String password) {
        return api().contentType(ContentType.JSON).body(Map.of("username", username, "password", password))
                .post("/api/auth/login");
    }

    /**
     * Um nome que nenhum outro teste usa (o limite de tentativas é por nome e IP).
     *
     * @return o nome
     */
    private static String uniqueName() {
        return "ninguem-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Login certo pelo navegador: cookie httpOnly, sem token no corpo.
     */
    @Test
    @DisplayName("login certo (navegador): 200 {ok:true} e cookie rayka_session httpOnly")
    void loginSetsCookie() {

        // ── 1. Entra com a senha certa.
        Response response = login(target.shopOwner(), ContractSeed.PASSWORD);

        // ── 2. Corpo sem token; cookie com os atributos do Node (sem Secure: COOKIE_SECURE=false no teste).
        assertJson(response, 200, "ok");
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("rayka_session=").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax").contains("Path=/").doesNotContainIgnoringCase("Secure");
        assertThat(response.getCookie("rayka_session")).matches("[A-Za-z0-9_-]{43}");
    }

    /**
     * Login pelo app: token no corpo, que vale como Bearer em /api/me.
     */
    @Test
    @DisplayName("login certo (app, x-client: mobile): token no corpo, aceito como Bearer")
    void loginReturnsTokenToTheApp() {

        // ── 1. Entra como o app.
        Response response = api().header("x-client", "mobile").contentType(ContentType.JSON)
                .body(Map.of("username", target.shopOwner(), "password", ContractSeed.PASSWORD))
                .post("/api/auth/login");

        // ── 2. O token do corpo abre /api/me.
        assertJson(response, 200, "ok-token");
        Response me = api().header("Authorization", "Bearer " + response.jsonPath().getString("token")).get("/api/me");
        assertJson(me, 200, "me");
        assertThat(me.jsonPath().getString("username")).isEqualTo(target.shopOwner());
    }

    /**
     * O cookie do login abre /api/me.
     */
    @Test
    @DisplayName("o cookie do login abre /api/me")
    void cookieOpensMe() {
        String cookie = login(target.shopOwner(), ContractSeed.PASSWORD).getCookie("rayka_session");
        assertJson(api().cookie("rayka_session", cookie).get("/api/me"), 200, "me");
    }

    /**
     * Senha errada e usuário inexistente: a mesma resposta.
     */
    @Test
    @DisplayName("senha errada ou usuário inexistente: 401 com a mesma mensagem")
    void wrongCredentials() {
        assertError(login(target.shopOwner(), "senha-errada-123"), 401, WRONG_LOGIN);
        assertError(login(uniqueName(), "qualquer-senha-1"), 401, WRONG_LOGIN);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/auth/login"), 401, WRONG_LOGIN);
    }

    /**
     * Oito erros passam; a nona tentativa é barrada, mesmo com a senha certa.
     */
    @Test
    @DisplayName("9ª tentativa: 429")
    void ninthAttemptIsBlocked() {

        // ── 1. Oito erros com o mesmo nome.
        String name = uniqueName();
        for (int i = 0; i < 8; i++) {
            assertError(login(name, "errada-" + i), 401, WRONG_LOGIN);
        }

        // ── 2. A nona é barrada antes de conferir a senha.
        assertError(login(name, "errada-9"), 429, TOO_MANY);
    }

    /**
     * Trocar a parte do X-Forwarded-For que o cliente escreve não zera a contagem (no Node, zerava).
     */
    @Test
    @ChangedFromNode("G05")
    @DisplayName("X-Forwarded-For forjado não reinicia a contagem")
    void forgedForwardedForDoesNotResetTheCount() {

        // ── 1. Oito erros, cada um com um "IP" forjado diferente à esquerda do IP real (o do proxy).
        String name = uniqueName();
        for (int i = 0; i < 8; i++) {
            api().header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.50").contentType(ContentType.JSON)
                    .body(Map.of("username", name, "password", "errada")).post("/api/auth/login");
        }

        // ── 2. A nona, com mais um forjado, continua barrada.
        Response ninth = api().header("X-Forwarded-For", "10.9.9.9, 203.0.113.50").contentType(ContentType.JSON)
                .body(Map.of("username", name, "password", "errada")).post("/api/auth/login");
        assertError(ninth, 429, TOO_MANY);
    }

    /**
     * Só a dona da loja entra no painel (no Node, qualquer conta entrava).
     */
    @Test
    @ChangedFromNode("G24")
    @DisplayName("conta que não é a dona: 401 mesmo com a senha certa")
    void onlyTheShopOwnerLogsIn() {
        String other = uniqueName();
        seed.owner(other);
        assertError(login(other, ContractSeed.PASSWORD), 401, WRONG_LOGIN);
    }

    /**
     * Senha gigante é recusada antes do scrypt.
     */
    @Test
    @ChangedFromNode("G05")
    @DisplayName("senha com mais de 200 caracteres: 400")
    void hugePassword() {
        Response response = login(target.shopOwner(), "x".repeat(201));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    /**
     * Logout apaga a sessão: o token deixa de valer e o cookie é apagado.
     */
    @Test
    @DisplayName("logout invalida o token e apaga o cookie")
    void logoutInvalidatesTheSession() {

        // ── 1. Uma sessão aberta.
        String bearer = seed.owner(target.shopOwner()).bearer();

        // ── 2. Sai.
        Response logout = api().header("Authorization", bearer).post("/api/auth/logout");

        // ── 3. 200, cookie apagado e o token não abre mais /api/me.
        assertJson(logout, 200, "ok");
        assertThat(logout.getHeader("Set-Cookie")).startsWith("rayka_session=;");
        assertError(api().header("Authorization", bearer).get("/api/me"), 401, SESSION_EXPIRED);
    }

    /**
     * Logout sem sessão também responde 200 (é idempotente).
     */
    @Test
    @DisplayName("logout sem sessão: 200")
    void logoutWithoutSession() {
        assertJson(api().post("/api/auth/logout"), 200, "ok");
    }

    /**
     * /api/me sem sessão: 401.
     */
    @Test
    @DisplayName("/api/me sem sessão: 401")
    void meWithoutSession() {
        assertError(api().get("/api/me"), 401, SESSION_EXPIRED);
    }

    /**
     * /api/me devolve só id e usuário.
     */
    @Test
    @DisplayName("/api/me com sessão: id e usuário")
    void meWithSession() {
        ContractSeed.Session session = seed.owner(target.shopOwner());
        Response me = api().header("Authorization", session.bearer()).get("/api/me");
        assertJson(me, 200, "me");
        assertThat(me.jsonPath().getLong("id")).isEqualTo(session.id());
    }

    /**
     * Cadastro de dona fechado por padrão (no Node, qualquer pessoa criava conta e entrava no painel).
     */
    @Test
    @ChangedFromNode("G01")
    @DisplayName("POST /api/auth/register fechado: 404")
    void registrationIsClosed() {
        Response response = api().contentType(ContentType.JSON)
                .body(Map.of("username", uniqueName(), "password", "senha-boa-123")).post("/api/auth/register");
        assertError(response, 404, "Não encontrado.");
    }
}
