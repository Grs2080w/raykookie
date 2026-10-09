package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.web.ErrorClassifier.ApiError;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Página de erro do servidor ({@code /error}), para onde vão os erros que não passam pelo
 * {@link GlobalErrorHandler}: um filtro que lança, um {@code sendError} ou uma recusa do próprio Tomcat.
 * Substitui o controller padrão do Spring Boot, que devolve HTML a navegadores: aqui a resposta é
 * sempre {@code {"error": "..."}} (G20), peça quem pedir.
 *
 * <p>Não é uma rota da API (não leva {@code @Access}): só o servidor chega aqui, por despacho interno.
 */
@Controller
public class JsonErrorController implements ErrorController {

    /** Log dos erros inesperados que chegam por aqui. */
    private static final Logger LOG = LoggerFactory.getLogger(JsonErrorController.class);

    /**
     * Responde o erro em JSON, com o status do erro original.
     *
     * @param request requisição do despacho de erro
     * @return a resposta de erro
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorBody> error(HttpServletRequest request) {

        // ── 1. Se houve exceção, classifica como o tratador global faria; senão, vale o status mandado.
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        ApiError api = classify(exception, request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));

        // ── 2. Falha do servidor: registra com o requestId.
        if (api.unexpected() && exception instanceof Throwable error) {
            LOG.error("Erro inesperado fora das rotas (requestId={})", RequestIdFilter.current().orElse("-"), error);
        }

        // ── 3. Responde sempre JSON.
        return ResponseEntity.status(api.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(api.message()));
    }

    /**
     * Classifica o erro do despacho.
     *
     * @param exception  a exceção original, se houver
     * @param statusCode o status mandado pelo servidor, se houver
     * @return o erro, sempre com uma resposta a dar
     */
    private static ApiError classify(Object exception, Object statusCode) {
        if (exception instanceof Throwable error) {
            ApiError api = ErrorClassifier.classify(error);
            if (api.hasResponse()) {
                return api;
            }
        }
        int status = 500;
        if (statusCode instanceof Integer code && exception == null) {
            status = code;
        }
        return ApiError.of(status, ErrorMessages.forStatus(status));
    }
}
