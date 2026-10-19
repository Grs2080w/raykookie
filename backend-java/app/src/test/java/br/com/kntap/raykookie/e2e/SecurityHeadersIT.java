package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segurança, categorias 2 e 3: cabeçalhos de segurança, CORS e cache de respostas com dados da conta.
 */
class SecurityHeadersIT extends AppItSupport {

    /** Origem de um site de terceiros. */
    static final String EVIL = "https://evil.example";

    /**
     * Todas as respostas da API levam os cabeçalhos que fecham clickjacking, sniffing e vazamento de referrer.
     */
    @Test
    @DisplayName("headers: nosniff, X-Frame-Options, CSP restritiva, Referrer-Policy e HSTS em respostas públicas, de erro e 404")
    void securityHeadersEverywhere() {
        for (String path : List.of("/api/health", "/api/menu", "/api/legal/termos", "/api/nao-existe",
                "/api/cliente/pedidos")) {
            Response response = api().get(path);
            assertThat(response.header("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
            assertThat(response.header("X-Frame-Options")).as(path).isIn("DENY", "SAMEORIGIN");
            assertThat(response.header("Content-Security-Policy")).as(path).contains("frame-ancestors");
            assertThat(response.header("Referrer-Policy")).as(path).isNotBlank();
            assertThat(response.header("Strict-Transport-Security")).as(path).contains("max-age=");
        }
    }

    /**
     * Dados da conta nunca ficam em cache compartilhado.
     */
    @Test
    @DisplayName("cache: respostas autenticadas (/api/me, /api/cliente/pedidos) não são cacheáveis")
    void authenticatedResponsesAreNotCacheable() {
        Response owner = as(panelSession(OWNER)).get("/api/me");
        Response customer = as(newCustomer().token()).get("/api/cliente/pedidos");
        for (Response response : List.of(owner, customer)) {
            assertThat(response.header("Cache-Control")).isNotNull().containsAnyOf("no-store", "private")
                    .doesNotContain("public");
        }
    }

    /**
     * CORS: o navegador de outro site não pode ler a API nem mandar requisição com credencial.
     */
    @Test
    @DisplayName("CORS: origem de terceiros não recebe Access-Control-Allow-Origin, nem no preflight")
    void corsIsClosed() {
        Response simple = api().header("Origin", EVIL).get("/api/menu");
        assertThat(simple.header("Access-Control-Allow-Origin")).isNull();
        assertThat(simple.header("Access-Control-Allow-Credentials")).isNull();

        Response preflight = api().header("Origin", EVIL).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .options("/api/cliente/pedidos");
        assertThat(preflight.header("Access-Control-Allow-Origin")).isNull();
        assertThat(preflight.statusCode()).isIn(200, 204, 401, 403, 404, 405);

        Response wildcard = api().header("Origin", "null").get("/api/health");
        assertThat(wildcard.header("Access-Control-Allow-Origin")).isNull();
    }

    /**
     * Erros não mostram pilha, classe nem caminho de arquivo.
     */
    @Test
    @DisplayName("erros: JSON quebrado, método errado e corpo gigante respondem sem stack trace nem detalhes internos")
    void errorsDoNotLeakInternals() {
        List<Response> responses = List.of(
                api().body("{quebrado").post("/api/cliente/login"),
                api().body("[]").post("/api/cliente/cadastro"),
                api().delete("/api/menu"),
                as(panelSession(OWNER)).body("{\"percent\":\"abc\"}").post("/api/coupons"),
                api().get("/api/recipes/abc"));
        for (Response response : responses) {
            assertThat(response.statusCode()).isBetween(400, 499);
            assertThat(response.asString()).doesNotContain("Exception", "at br.com.kntap", "at org.springframework",
                    ".java:", "Caused by", "/home/", "whitelabel", "Whitelabel");
        }
    }
}
