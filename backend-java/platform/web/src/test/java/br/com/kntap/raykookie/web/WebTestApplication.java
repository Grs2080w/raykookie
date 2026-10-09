package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.kernel.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Aplicação mínima dos testes do módulo web: só a borda HTTP comum e rotas de teste que provocam
 * cada tipo de erro.
 */
@SpringBootApplication
public class WebTestApplication {

    /**
     * Rotas de teste.
     */
    @RestController
    static class TestRoutes {

        /**
         * Rota só com GET (um DELETE nela dá 405).
         *
         * @return objeto vazio
         */
        @GetMapping("/api/menu")
        Map<String, Object> menu() {
            return Map.of();
        }

        /**
         * Lê a foto inteira pelo fluxo, como a rota de foto de receita faz.
         *
         * @param id      id da receita
         * @param request requisição
         * @return quantos bytes foram lidos
         * @throws IOException se a leitura falhar (inclusive acima do limite)
         */
        @PutMapping("/api/recipes/{id}/photo")
        Map<String, Object> photo(@PathVariable long id, HttpServletRequest request) throws IOException {
            return Map.of("bytes", request.getInputStream().readAllBytes().length);
        }

        /**
         * Devolve o JSON recebido.
         *
         * @param body corpo JSON
         * @return o mesmo corpo
         */
        @PostMapping("/api/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }

        /**
         * Valida um corpo com mensagens próprias e padrão.
         *
         * @param body corpo validado
         * @return confirmação
         */
        @PostMapping("/api/validate")
        Map<String, Object> validate(@Valid @RequestBody Item body) {
            return Map.of("ok", true);
        }

        /**
         * Recebe fotos em multipart, como o pedido de devolução.
         *
         * @param fotos arquivos enviados
         * @return quantos arquivos chegaram
         */
        @PostMapping("/api/cliente/feedback")
        Map<String, Object> upload(@RequestParam("fotos") List<MultipartFile> fotos) {
            return Map.of("files", fotos.size());
        }

        /**
         * Falha inesperada com detalhe interno.
         *
         * @return nunca devolve
         */
        @GetMapping("/api/boom")
        Map<String, Object> boom() {
            throw new IllegalStateException("detalhe interno: senha=123");
        }

        /**
         * Regra de negócio que recusa.
         *
         * @return nunca devolve
         */
        @GetMapping("/api/domain")
        Map<String, Object> domain() {
            throw new NotFoundException("Receita não encontrada.");
        }

        /**
         * Violação de restrição do banco com o código SQL pedido.
         *
         * @param state código SQL
         * @return nunca devolve
         */
        @GetMapping("/api/sql/{state}")
        Map<String, Object> sql(@PathVariable String state) {
            throw new DataIntegrityViolationException("violação", new SQLException("erro do banco", state));
        }

        /**
         * Banco fora do ar.
         *
         * @return nunca devolve
         */
        @GetMapping("/api/no-db")
        Map<String, Object> noDatabase() {
            throw new CannotGetJdbcConnectionException("sem conexão", new SQLException("timeout"));
        }

        /**
         * Erro mandado direto ao servidor (vai para /error, fora do tratador global).
         *
         * @param response resposta
         * @throws IOException se a escrita falhar
         */
        @GetMapping("/api/send-error")
        void sendError(HttpServletResponse response) throws IOException {
            response.sendError(400);
        }
    }

    /**
     * Corpo validado.
     *
     * @param name     nome, com mensagem própria
     * @param quantity quantidade, com a mensagem padrão (em inglês) do Jakarta Validation
     */
    record Item(@NotBlank(message = "Informe o nome.") String name, @Min(1) int quantity) {
    }
}
