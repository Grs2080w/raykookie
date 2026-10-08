package br.com.kntap.raykookie.customers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * As rotas da conta do cliente pela rede, de ponta a ponta no módulo (a suíte de contrato repete os mesmos
 * casos contra o Node e o Java).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class CustomerAccountWebIT extends PostgresTestBase {

    /** Porta do servidor de teste. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Sem contadores de tentativas de testes anteriores.
     */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM rate_limits").update();
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    private RequestSpecification api() {
        return RestAssured.given().port(port).contentType(ContentType.JSON);
    }

    /**
     * Cadastra um cliente novo pelo app.
     *
     * @return o token
     */
    private String signUp() {
        String email = "web-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com";
        Response created = api().header("x-client", "mobile")
                .body(Map.of("name", "Cliente Web", "email", email, "password", "senha-boa-123"))
                .post("/api/cliente/cadastro");
        assertThat(created.statusCode()).isEqualTo(201);
        return created.jsonPath().getString("token");
    }

    /**
     * Cadastro: validações, duplicado e limite por IP.
     */
    @Test
    @DisplayName("cadastro: validações, duplicado e 6º do mesmo IP barrado")
    void signUpRules() {

        // ── 1. Validações.
        assertThat(error(api().body(Map.of("name", "A")).post("/api/cliente/cadastro"))).isEqualTo("Informe seu nome.");
        assertThat(error(api().body(Map.of("name", "Ana", "email", "x")).post("/api/cliente/cadastro")))
                .isEqualTo("Esse e-mail não parece certo. Confira e tente de novo.");
        assertThat(error(api().body(Map.of("name", "Ana", "email", "a@b.com", "password", "x".repeat(201)))
                .post("/api/cliente/cadastro"))).isEqualTo("A senha é longa demais.");

        // ── 2. Duplicado.
        Response duplicate = api().body(Map.of("name", "Ana", "email", "ANA@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro");
        assertThat(duplicate.statusCode()).isEqualTo(409);

        // ── 3. Até 5 cadastros por IP numa hora (o duplicado acima já contou).
        for (int i = 0; i < 4; i++) {
            signUp();
        }
        assertThat(api().body(Map.of("name", "Ana", "email", "mais@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro").statusCode()).isEqualTo(429);
    }

    /**
     * Login: certo, errado (mensagem única) e o 9º barrado.
     */
    @Test
    @DisplayName("login: certo, recusas com a mesma mensagem e 9ª tentativa barrada")
    void login() {

        // ── 1. Certo pelo navegador (cookie) e errado.
        Response ok = api().body(Map.of("email", "ana@teste.com", "password", "senha-de-teste-123"))
                .post("/api/cliente/login");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.getCookie("rk_customer")).hasSize(43);
        assertThat(error(api().body(Map.of("email", "ana@teste.com", "password", "x".repeat(300)))
                .post("/api/cliente/login"))).startsWith("E-mail ou senha incorretos.");

        // ── 2. Oito erros e a nona barrada.
        for (int i = 0; i < 7; i++) {
            api().body(Map.of("email", "bia@teste.com", "password", "errada")).post("/api/cliente/login");
        }
        assertThat(api().body(Map.of("email", "bia@teste.com", "password", "errada")).post("/api/cliente/login")
                .statusCode()).isEqualTo(401);
        assertThat(api().body(Map.of("email", "bia@teste.com", "password", "senha-de-teste-123"))
                .post("/api/cliente/login").statusCode()).isEqualTo(429);
    }

    /**
     * Perfil, meus dados, Google, solicitações e saída, com um cliente novo.
     */
    @Test
    @DisplayName("perfil, meus dados, desvincular Google, solicitações e saída")
    void accountRoutes() {

        // ── 1. Perfil e correção.
        String token = signUp();
        RequestSpecification as = api().header("Authorization", "Bearer " + token);
        assertThat(as.get("/api/cliente/me").jsonPath().getString("name")).isEqualTo("Cliente Web");
        assertThat(error(api().header("Authorization", "Bearer " + token).body(Map.of("name", "Ana", "phone", "1"))
                .patch("/api/cliente/me"))).isEqualTo("Telefone inválido. Use DDD + número.");
        assertThat(error(api().header("Authorization", "Bearer " + token).body(Map.of("name", "Ana", "cep", "1"))
                .patch("/api/cliente/me"))).isEqualTo("CEP inválido.");
        assertThat(api().header("Authorization", "Bearer " + token).body(Map.of("name", "Ana Web", "phone",
                "11 3333-4444")).patch("/api/cliente/me").statusCode()).isEqualTo(200);

        // ── 2. Meus dados e Google.
        Response data = api().header("Authorization", "Bearer " + token).get("/api/cliente/me/dados");
        assertThat(data.jsonPath().getString("phone")).isEqualTo("1133334444");
        assertThat(data.jsonPath().getList("request_types")).hasSize(5);
        assertThat(api().header("Authorization", "Bearer " + token).delete("/api/cliente/me/google").statusCode())
                .isEqualTo(409);

        // ── 3. Solicitação; a dona vê e responde.
        assertThat(api().header("Authorization", "Bearer " + token).body(Map.of("type", "x"))
                .post("/api/cliente/me/privacidade").statusCode()).isEqualTo(400);
        int id = api().header("Authorization", "Bearer " + token).body(Map.of("type", "outro",
                "details", "Quero saber de onde vieram meus dados.")).post("/api/cliente/me/privacidade")
                .jsonPath().getInt("id");
        String owner = ownerToken();
        assertThat(api().header("Authorization", "Bearer " + owner).get("/api/privacy-requests")
                .jsonPath().getList("id", Integer.class)).contains(id);
        assertThat(api().header("Authorization", "Bearer " + owner).body(Map.of("answer", "Respondido."))
                .post("/api/privacy-requests/" + id).statusCode()).isEqualTo(200);
        assertThat(api().header("Authorization", "Bearer " + owner).body(Map.of("answer", "De novo."))
                .post("/api/privacy-requests/abc").statusCode()).isEqualTo(404);

        // ── 4. Saída.
        assertThat(api().header("Authorization", "Bearer " + token).post("/api/cliente/logout").statusCode())
                .isEqualTo(200);
        assertThat(api().header("Authorization", "Bearer " + token).get("/api/cliente/me").statusCode())
                .isEqualTo(401);
    }

    /**
     * Desvincular o Google: conta com senha desliga; conta só do Google não.
     */
    @Test
    @DisplayName("desvincular Google: com senha desliga; só Google recusa")
    void unlinkGoogle() {

        // ── 1. Conta com senha e Google.
        String token = signUp();
        jdbc.sql("UPDATE customers SET google_sub = 'g-' || id WHERE id = (SELECT customer_id FROM customer_sessions "
                + "WHERE token_hash = encode(sha256(convert_to(:t, 'UTF8')), 'hex'))").param("t", token).update();
        assertThat(api().header("Authorization", "Bearer " + token).delete("/api/cliente/me/google").statusCode())
                .isEqualTo(200);

        // ── 2. Conta só do Google.
        jdbc.sql("UPDATE customers SET google_sub = 'g2-' || id, password_hash = NULL WHERE id = (SELECT customer_id "
                + "FROM customer_sessions WHERE token_hash = encode(sha256(convert_to(:t, 'UTF8')), 'hex'))")
                .param("t", token).update();
        assertThat(error(api().header("Authorization", "Bearer " + token).delete("/api/cliente/me/google")))
                .startsWith("Sua conta só entra pelo Google.");

        // ── 3. Excluir conta só do Google não pede senha.
        assertThat(api().header("Authorization", "Bearer " + token).body(Map.of("confirm", "EXCLUIR"))
                .delete("/api/cliente/me").statusCode()).isEqualTo(200);
    }

    /**
     * Token da dona de teste.
     *
     * @return o token
     */
    private String ownerToken() {
        return api().header("x-client", "mobile").body(Map.of("username", "dona", "password", "senha-de-teste-123"))
                .post("/api/auth/login").jsonPath().getString("token");
    }

    /**
     * Mensagem de erro da resposta.
     *
     * @param response resposta
     * @return o campo error
     */
    private static String error(Response response) {
        return response.jsonPath().getString("error");
    }
}
