package br.com.kntap.raykookie.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Todo erro sai como {@code {"error": "..."}} com o status certo, pela rede, como o site e o app veem (G20).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GlobalErrorHandlerIT {

    /** Porta aleatória do servidor de teste. */
    @LocalServerPort
    int port;

    /**
     * Requisição pela rede para o servidor de teste.
     *
     * @return a requisição
     */
    private RequestSpecification api() {
        return RestAssured.given().port(port);
    }

    /**
     * Confere status, tipo JSON, mensagem e o requestId na resposta.
     *
     * @param response resposta recebida
     * @param status   status esperado
     * @param message  mensagem esperada
     */
    private static void assertError(Response response, int status, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.asString()).isEqualTo(new ErrorBody(message).toJson());
        assertThat(response.header(RequestIdFilter.HEADER)).isNotBlank();
    }

    /**
     * Rota inexistente: 404 JSON (no Node, a página HTML do Next).
     */
    @Test
    @DisplayName("rota inexistente: 404 em JSON")
    void unknownRoute() {
        assertError(api().get("/api/nao-existe"), 404, "Não encontrado.");
    }

    /**
     * Método que a rota não aceita: 405 JSON com o cabeçalho Allow.
     */
    @Test
    @DisplayName("DELETE /api/menu: 405 em JSON com Allow")
    void methodNotAllowed() {

        // ── 1. Chama com o método errado.
        Response response = api().delete("/api/menu");

        // ── 2. 405 em JSON, dizendo quais métodos valem.
        assertError(response, 405, "Método não permitido.");
        assertThat(response.header("Allow")).contains("GET");
    }

    /**
     * Falha inesperada: 500 com a mensagem genérica, sem o detalhe interno nem stack trace.
     */
    @Test
    @DisplayName("exceção inesperada: 500 em JSON sem detalhe interno")
    void unexpectedError() {

        // ── 1. Rota que lança com um detalhe que não pode vazar.
        Response response = api().get("/api/boom");

        // ── 2. Só a mensagem genérica.
        assertError(response, 500, "Algo deu errado no servidor. Tente de novo.");
        assertThat(response.asString()).doesNotContain("senha", "Exception", "at br.com");
    }

    /**
     * Regra de negócio: o status e a mensagem do próprio erro.
     */
    @Test
    @DisplayName("DomainException: o status e a mensagem dela")
    void domainError() {
        assertError(api().get("/api/domain"), 404, "Receita não encontrada.");
    }

    /**
     * Erros do banco com as mesmas mensagens do Node (backend/lib/api.ts).
     *
     * @param state   código SQL
     * @param status  status esperado
     * @param message mensagem esperada
     */
    @ParameterizedTest(name = "SQL {0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "22003 | 400 | Algum número está grande demais.",
        "23505 | 409 | Já existe um registro com esse nome.",
        "23503 | 409 | Este item está em uso e não pode ser removido.",
        "23514 | 400 | Algum valor está fora do permitido.",
        "23502 | 500 | Algo deu errado no servidor. Tente de novo.",
    })
    @DisplayName("violação de restrição do banco: mensagem pelo código SQL")
    void databaseConstraint(String state, int status, String message) {
        assertError(api().get("/api/sql/" + state), status, message);
    }

    /**
     * Banco fora do ar: 503.
     */
    @Test
    @DisplayName("sem conexão com o banco: 503")
    void databaseDown() {
        assertError(api().get("/api/no-db"), 503, "Serviço indisponível. Tente de novo em instantes.");
    }

    /**
     * JSON quebrado: 400 (no Node virava 500).
     */
    @Test
    @DisplayName("JSON quebrado: 400 Dados inválidos.")
    void unreadableJson() {
        assertError(api().contentType("application/json").body("{nao é json").post("/api/echo"),
                400, "Dados inválidos.");
    }

    /**
     * Campo inválido com mensagem própria: a mensagem do campo.
     */
    @Test
    @DisplayName("campo inválido com mensagem em português: a mensagem do campo")
    void validationWithOwnMessage() {
        assertError(api().contentType("application/json").body("{\"name\":\"\",\"quantity\":1}").post("/api/validate"),
                400, "Informe o nome.");
    }

    /**
     * Campo inválido só com a mensagem padrão (em inglês): a mensagem genérica.
     */
    @Test
    @DisplayName("campo inválido sem mensagem própria: Dados inválidos.")
    void validationWithDefaultMessage() {
        assertError(api().contentType("application/json").body("{\"name\":\"x\",\"quantity\":0}").post("/api/validate"),
                400, "Dados inválidos.");
    }

    /**
     * Erro mandado direto ao servidor ({@code sendError}) passa por {@code /error}: também JSON,
     * inclusive para um navegador que pede HTML.
     */
    @Test
    @DisplayName("erro via /error: JSON mesmo pedindo HTML")
    void servletErrorPageIsJson() {

        // ── 1. Pede como cliente de API e como navegador.
        Response asApi = api().accept("application/json").get("/api/send-error");
        Response asBrowser = api().accept("text/html").get("/api/send-error");

        // ── 2. As duas respostas são o JSON da API.
        assertError(asApi, 400, "Dados inválidos.");
        assertError(asBrowser, 400, "Dados inválidos.");
    }

    /**
     * O requestId recebido de fora é mantido quando é seguro, e trocado quando não é.
     */
    @Test
    @DisplayName("X-Request-Id seguro é mantido; inseguro é trocado")
    void requestIdIsEchoedWhenSafe() {

        // ── 1. Um id seguro (como o do Mercado Pago) e um com quebra de linha forjada.
        String kept = api().header(RequestIdFilter.HEADER, "abc-123_x.y").get("/api/menu")
                .header(RequestIdFilter.HEADER);
        String replaced = api().header(RequestIdFilter.HEADER, "x\tforjado").get("/api/menu")
                .header(RequestIdFilter.HEADER);

        // ── 2. O seguro volta igual; o outro vira um UUID novo.
        assertThat(kept).isEqualTo("abc-123_x.y");
        assertThat(replaced).matches("[0-9a-f-]{36}");
    }
}
