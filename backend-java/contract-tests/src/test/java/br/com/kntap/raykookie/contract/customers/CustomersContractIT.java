package br.com.kntap.raykookie.contract.customers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractSeed.Session;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conta do cliente: cadastro, login, Google, "Meus dados" (LGPD) e as solicitações de privacidade (Tarefa 4.2).
 */
class CustomersContractIT extends ContractTestBase {

    /** Mensagem do Node para rota de cliente sem sessão. */
    private static final String SESSION_EXPIRED = "Sua sessão expirou. Entre de novo para continuar.";

    /**
     * Requisição com um X-Forwarded-For só deste teste (os limites por IP não se misturam).
     *
     * @return a requisição
     */
    private static RequestSpecification fresh() {
        return api().header("X-Forwarded-For", ContractSeed.uniqueForwardedFor()).contentType(ContentType.JSON);
    }

    /**
     * Requisição como o cliente da sessão.
     *
     * @param session sessão
     * @return a requisição
     */
    private static RequestSpecification as(Session session) {
        return api().header("Authorization", session.bearer()).contentType(ContentType.JSON);
    }

    /**
     * Cadastro certo: 201, cookie rk_customer e a sessão abre /api/cliente/me.
     */
    @Test
    @DisplayName("cadastro: 201, cookie rk_customer, sessão aberta")
    void signUp() {

        // ── 1. Cadastra com e-mail em maiúsculas e espaços.
        String email = ContractSeed.uniqueEmail("novo");
        Response response = fresh().body(Map.of("name", "  Ana  Cliente ", "email", " " + email.toUpperCase(),
                "password", "senha-boa-123")).post("/api/cliente/cadastro");

        // ── 2. 201 {ok:true} e o cookie abre a conta, com o e-mail em minúsculas.
        assertJson(response, 201, "ok");
        String cookie = response.getCookie("rk_customer");
        assertThat(response.getHeader("Set-Cookie")).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        Response me = api().cookie("rk_customer", cookie).get("/api/cliente/me");
        assertJson(me, 200, "customer");
        assertThat(me.jsonPath().getString("email")).isEqualTo(email);
        assertThat(me.jsonPath().getString("name")).isEqualTo("Ana  Cliente");
    }

    /**
     * Cadastro pelo app: token no corpo.
     */
    @Test
    @DisplayName("cadastro pelo app: 201 com token")
    void signUpFromTheApp() {
        Response response = fresh().header("x-client", "mobile").body(Map.of("name", "Bia",
                "email", ContractSeed.uniqueEmail("app"), "password", "senha-boa-123")).post("/api/cliente/cadastro");
        assertJson(response, 201, "ok-token");
    }

    /**
     * Validações do cadastro, com as mensagens do Node.
     */
    @Test
    @DisplayName("cadastro inválido: 400 com a mensagem de cada campo; e-mail repetido: 409")
    void signUpValidation() {

        // ── 1. Cada campo errado.
        String email = ContractSeed.uniqueEmail("valida");
        assertError(fresh().body(Map.of("name", "A", "email", email, "password", "senha-boa-123"))
                .post("/api/cliente/cadastro"), 400, "Informe seu nome.");
        assertError(fresh().body(Map.of("name", "Ana", "email", "sem-arroba", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro"), 400, "Esse e-mail não parece certo. Confira e tente de novo.");
        assertError(fresh().body(Map.of("name", "Ana", "email", email, "password", "curta"))
                .post("/api/cliente/cadastro"), 400, "A senha precisa ter pelo menos 8 caracteres.");
        assertError(fresh().body(Map.of("name", "Ana", "email", email, "password", "x".repeat(201)))
                .post("/api/cliente/cadastro"), 400, "A senha é longa demais.");

        // ── 2. E-mail já cadastrado (sem diferenciar maiúsculas).
        seed.customer(email);
        assertError(fresh().body(Map.of("name", "Ana", "email", email.toUpperCase(), "password", "senha-boa-123"))
                .post("/api/cliente/cadastro"), 409, "Já existe uma conta com esse e-mail. Entre com ela ou use o Google.");
    }

    /**
     * Sexto cadastro do mesmo IP em uma hora: 429 (no Node, eram 8 em 10 minutos, com a chave forjável).
     */
    @Test
    @ChangedFromNode("G05")
    @DisplayName("6º cadastro do mesmo IP em 1 h: 429")
    void signUpRateLimit() {

        // ── 1. Cinco cadastros do mesmo IP real.
        String ip = ContractSeed.uniqueForwardedFor();
        for (int i = 0; i < 5; i++) {
            assertThat(api().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
                    .body(Map.of("name", "Ana", "email", ContractSeed.uniqueEmail("lote"), "password", "senha-boa-123"))
                    .post("/api/cliente/cadastro").statusCode()).isEqualTo(201);
        }

        // ── 2. O sexto é barrado.
        assertError(api().header("X-Forwarded-For", ip).contentType(ContentType.JSON)
                .body(Map.of("name", "Ana", "email", ContractSeed.uniqueEmail("lote"), "password", "senha-boa-123"))
                .post("/api/cliente/cadastro"), 429, "Muitas tentativas. Espere alguns minutos e tente de novo.");
    }

    /**
     * Login certo e errado.
     */
    @Test
    @DisplayName("login: certo abre a sessão; errado 401")
    void login() {

        // ── 1. Cliente com a senha conhecida.
        String email = ContractSeed.uniqueEmail("login");
        seed.customer(email);

        // ── 2. Certo, com e-mail em maiúsculas.
        Response ok = fresh().header("x-client", "mobile")
                .body(Map.of("email", email.toUpperCase(), "password", ContractSeed.PASSWORD)).post("/api/cliente/login");
        assertJson(ok, 200, "ok-token");
        assertJson(api().header("Authorization", "Bearer " + ok.jsonPath().getString("token")).get("/api/cliente/me"),
                200, "customer");

        // ── 3. Errado.
        assertError(fresh().body(Map.of("email", email, "password", "errada-123")).post("/api/cliente/login"),
                401, wrongLoginMessage());
    }

    /**
     * Conta só do Google, no Node: mensagem que revela a conta (G19 troca no Java).
     */
    @Test
    @DisplayName("Node: conta só do Google tem mensagem própria")
    void googleOnlyLoginOnNode() {
        Assumptions.assumeFalse(target.isJava(), "comportamento do Node, trocado no Java (G19)");
        String email = ContractSeed.uniqueEmail("google");
        seed.googleCustomer(email);
        assertError(fresh().body(Map.of("email", email, "password", "qualquer-123")).post("/api/cliente/login"),
                401, "Essa conta foi criada com o Google. Toque em “Continuar com o Google”.");
    }

    /**
     * No Java a mensagem é a mesma para conta só do Google, senha errada e e-mail inexistente (G19).
     */
    @Test
    @ChangedFromNode("G19")
    @DisplayName("Java: conta só do Google, senha errada e e-mail inexistente têm a mesma mensagem")
    void sameMessageForEveryLoginFailure() {
        String google = ContractSeed.uniqueEmail("google");
        seed.googleCustomer(google);
        assertError(fresh().body(Map.of("email", google, "password", "qualquer-123")).post("/api/cliente/login"),
                401, wrongLoginMessage());
        assertError(fresh().body(Map.of("email", ContractSeed.uniqueEmail("nada"), "password", "qualquer-123"))
                .post("/api/cliente/login"), 401, wrongLoginMessage());
    }

    /**
     * A mensagem de login recusado em cada alvo.
     *
     * @return a mensagem esperada
     */
    private static String wrongLoginMessage() {
        return target.isJava()
                ? "E-mail ou senha incorretos. Se você criou a conta com o Google, use “Continuar com o Google”."
                : "E-mail ou senha incorretos.";
    }

    /**
     * Logout apaga a sessão.
     */
    @Test
    @DisplayName("logout: a sessão deixa de valer e o cookie é apagado")
    void logout() {
        Session session = seed.customer(ContractSeed.uniqueEmail("sair"));
        Response out = as(session).post("/api/cliente/logout");
        assertJson(out, 200, "ok");
        assertThat(out.getHeader("Set-Cookie")).startsWith("rk_customer=;");
        assertError(as(session).get("/api/cliente/me"), 401, "Entre para continuar.");
    }

    /**
     * /api/cliente/me sem sessão tem mensagem própria; as outras rotas, a mensagem padrão.
     */
    @Test
    @DisplayName("sem sessão: /me 401 \"Entre para continuar.\"; as demais 401 padrão")
    void withoutSession() {
        assertError(api().get("/api/cliente/me"), 401, "Entre para continuar.");
        assertError(api().get("/api/cliente/me/dados"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").patch("/api/cliente/me"), 401, SESSION_EXPIRED);
    }

    /**
     * Corrigir os dados: valida e grava só dígitos em telefone e CEP.
     */
    @Test
    @DisplayName("PATCH /api/cliente/me: valida e grava")
    void updateProfile() {

        // ── 1. Dados inválidos.
        Session session = seed.customer(ContractSeed.uniqueEmail("perfil"));
        assertError(as(session).body(Map.of("name", "A")).patch("/api/cliente/me"), 400, "Informe seu nome.");
        assertError(as(session).body(Map.of("name", "Ana", "phone", "123")).patch("/api/cliente/me"), 400,
                "Telefone inválido. Use DDD + número.");
        assertError(as(session).body(Map.of("name", "Ana", "cep", "123")).patch("/api/cliente/me"), 400, "CEP inválido.");

        // ── 2. Dados certos com máscara.
        assertJson(as(session).body(Map.of("name", " Ana   Souza ", "phone", "(11) 98888-7777", "cep", "01310-100",
                "street", "Av. Paulista", "number", "1000", "city", "São Paulo")).patch("/api/cliente/me"), 200, "ok");

        // ── 3. Gravado limpo.
        Response me = as(session).get("/api/cliente/me");
        assertThat(me.jsonPath().getString("name")).isEqualTo("Ana Souza");
        assertThat(me.jsonPath().getString("phone")).isEqualTo("11988887777");
        assertThat(me.jsonPath().getString("cep")).isEqualTo("01310100");
    }

    /**
     * "Meus dados", exportação, desvincular o Google e solicitações.
     */
    @Test
    @DisplayName("Meus dados, exportar, Google e solicitações de privacidade")
    void myData() {

        // ── 1. Meus dados no formato do Node (a dona existe: as solicitações vão para ela).
        seed.owner(target.shopOwner());
        Session session = seed.customer(ContractSeed.uniqueEmail("dados"));
        Response data = as(session).get("/api/cliente/me/dados");
        assertJson(data, 200, "customer-data");
        assertThat(data.jsonPath().getBoolean("has_password")).isTrue();

        // ── 2. Exportação como anexo JSON.
        Response export = as(session).get("/api/cliente/me/exportar");
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"meus-dados-raykookie.json\"");
        assertThat(export.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(export.jsonPath().getMap("$")).containsOnlyKeys("exported_at", "account", "orders",
                "privacy_requests", "notification_devices");

        // ── 3. Sem Google para desvincular.
        assertError(as(session).delete("/api/cliente/me/google"), 409, "Sua conta não está ligada ao Google.");

        // ── 4. Solicitações: tipo inválido, "outro" curto e uma válida.
        assertError(as(session).body(Map.of("type", "x")).post("/api/cliente/me/privacidade"), 400,
                "Escolha o tipo de solicitação.");
        assertError(as(session).body(Map.of("type", "outro", "details", "curto")).post("/api/cliente/me/privacidade"),
                400, "Conte um pouco mais sobre o que você precisa.");
        Response created = as(session).body(Map.of("type", "acesso", "details", "Quero saber o que vocês têm."))
                .post("/api/cliente/me/privacidade");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getBoolean("ok")).isTrue();
        assertThat(created.jsonPath().getInt("id")).isPositive();
        assertThat(as(session).get("/api/cliente/me/dados").jsonPath().getInt("open_requests")).isEqualTo(1);
    }

    /**
     * A dona vê e responde as solicitações.
     */
    @Test
    @DisplayName("dona: lista e responde a solicitação; repetir dá 404")
    void ownerAnswersRequests() {

        // ── 1. Um cliente faz uma solicitação para a dona.
        Session owner = seed.owner(target.shopOwner());
        Session customer = seed.customer(ContractSeed.uniqueEmail("pedido-lgpd"));
        int id = as(customer).body(Map.of("type", "revogacao", "details", "Revogo o consentimento."))
                .post("/api/cliente/me/privacidade").jsonPath().getInt("id");

        // ── 2. A dona vê na lista.
        Response list = as(owner).get("/api/privacy-requests");
        assertJson(list, 200, "privacy-requests");
        assertThat(list.jsonPath().getList("id", Integer.class)).contains(id);

        // ── 3. Resposta curta, certa e repetida.
        assertError(as(owner).body(Map.of("answer", "ok")).post("/api/privacy-requests/" + id), 400,
                "Escreva a resposta ao cliente.");
        assertJson(as(owner).body(Map.of("answer", "Feito, consentimento revogado.")).post("/api/privacy-requests/" + id),
                200, "ok");
        assertError(as(owner).body(Map.of("answer", "De novo.")).post("/api/privacy-requests/" + id), 404,
                "Solicitação não encontrada ou já concluída.");

        // ── 4. Sem sessão da dona: 401.
        assertError(api().get("/api/privacy-requests"), 401, "Sessão expirada. Entre novamente.");
    }

    /**
     * Excluir a conta: confirmação, senha e anonimização.
     */
    @Test
    @DisplayName("excluir conta: exige EXCLUIR e a senha; anonimiza e encerra a sessão")
    void deleteAccount() {

        // ── 1. Confirmação e senha erradas.
        Session session = seed.customer(ContractSeed.uniqueEmail("excluir"));
        assertError(as(session).body(Map.of("confirm", "sim")).delete("/api/cliente/me"), 400,
                "Digite EXCLUIR para confirmar.");
        assertError(as(session).body(Map.of("confirm", "excluir", "password", "errada")).delete("/api/cliente/me"), 403,
                "Senha incorreta.");

        // ── 2. Certo.
        Response deleted = as(session).body(Map.of("confirm", " excluir ", "password", ContractSeed.PASSWORD))
                .delete("/api/cliente/me");
        assertJson(deleted, 200, "ok");

        // ── 3. Sessão encerrada e dados anonimizados.
        assertError(as(session).get("/api/cliente/me"), 401, "Entre para continuar.");
        assertThat(seed.value("SELECT email FROM customers WHERE id = ?", session.id()))
                .isEqualTo("excluido-" + session.id() + "@invalid");
        assertThat(seed.value("SELECT count(*) FROM customer_sessions WHERE customer_id = ?", session.id()))
                .isEqualTo(0L);
    }

    /**
     * Google sem configuração: redireciona com o erro; o app recebe 503.
     */
    @Test
    @DisplayName("Google sem configuração: redirect com erro; token do app 503")
    void googleUnavailable() {

        // ── 1. Início do login pelo site.
        Response start = api().get("/api/cliente/google");
        assertThat(start.statusCode()).isIn(302, 307);
        assertThat(start.getHeader("Location")).endsWith("/cliente/entrar?erro=google-indisponivel");

        // ── 2. Volta do Google sem state válido.
        Response callback = api().get("/api/cliente/google/callback?code=abc&state=xyz");
        assertThat(callback.statusCode()).isIn(302, 307);
        assertThat(callback.getHeader("Location")).endsWith("/cliente/entrar?erro=google-invalido");

        // ── 3. Login pelo app.
        assertError(api().contentType(ContentType.JSON).body(Map.of("idToken", "x".repeat(30)))
                .post("/api/cliente/google/token"), 503,
                "O login com o Google ainda não está disponível. Entre com e-mail e senha.");
    }
}
