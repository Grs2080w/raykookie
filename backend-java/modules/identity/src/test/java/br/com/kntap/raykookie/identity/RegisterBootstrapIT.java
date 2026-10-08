package br.com.kntap.raykookie.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import br.com.kntap.raykookie.testing.TestDatabase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Instalação nova (G01): com {@code REGISTRATION_ENABLED=true} e nenhuma conta, o servidor sobe com a dona
 * pendente, e o cadastro só aceita o usuário do {@code SHOP_OWNER}, uma vez.
 */
class RegisterBootstrapIT extends PostgresTestBase {

    /**
     * Cadastro da dona numa instalação nova, do começo ao fim.
     */
    @Test
    @DisplayName("instalação nova: só o SHOP_OWNER se cadastra, uma vez, e já entra")
    void freshInstall() {

        // ── 1. Banco vazio, cadastro ligado, dona "nova-dona" ainda inexistente.
        TestDatabase empty = newDatabase("instalacao");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(IdentityTestApplication.class)
                .properties("server.port=0", "rayka.db.url=" + empty.jdbcUrl(),
                        "rayka.db.username=" + empty.username(), "rayka.db.password=" + empty.password(),
                        "rayka.shop-owner=nova-dona", "rayka.registration-enabled=true", "rayka.jobs-enabled=false")
                .run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();

            // ── 2. Antes do cadastro, rota da dona fecha; outro nome e senha curta são recusados.
            assertThat(RestAssured.given().port(port).get("/api/me").statusCode()).isEqualTo(401);
            assertThat(register(port, "intrusa", "senha-boa-123").statusCode()).isEqualTo(400);
            assertThat(register(port, "nova-dona", "curta").jsonPath().getString("error"))
                    .isEqualTo("A senha precisa ter pelo menos 8 caracteres.");

            // ── 3. O cadastro certo devolve o token do app, que já abre /api/me.
            Response created = RestAssured.given().port(port).header("x-client", "mobile")
                    .contentType(ContentType.JSON)
                    .body(Map.of("username", "nova-dona", "password", "senha-boa-123")).post("/api/auth/register");
            assertThat(created.statusCode()).isEqualTo(200);
            String token = created.jsonPath().getString("token");
            Response me = RestAssured.given().port(port).header("Authorization", "Bearer " + token).get("/api/me");
            assertThat(me.jsonPath().getString("username")).isEqualTo("nova-dona");

            // ── 4. Depois disso, o cadastro "não existe" mais, e o login funciona.
            assertThat(register(port, "nova-dona", "outra-senha-123").statusCode()).isEqualTo(404);
            assertThat(RestAssured.given().port(port).contentType(ContentType.JSON)
                    .body(Map.of("username", "nova-dona", "password", "senha-boa-123")).post("/api/auth/login")
                    .statusCode()).isEqualTo(200);
        }
    }

    /**
     * Pede o cadastro.
     *
     * @param port     porta do servidor
     * @param username usuário
     * @param password senha
     * @return a resposta
     */
    private static Response register(int port, String username, String password) {
        return RestAssured.given().port(port).contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password)).post("/api/auth/register");
    }
}
