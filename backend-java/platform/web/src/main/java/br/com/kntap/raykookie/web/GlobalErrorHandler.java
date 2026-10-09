package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.web.ErrorClassifier.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Todo erro que escapa de uma rota sai como {@code {"error": "..."}} com o status certo (G20): nunca a
 * página HTML do Spring, nunca o stack trace no corpo. Falhas do servidor são registradas como
 * {@code ERROR} com o {@code requestId}, que também volta no cabeçalho {@code X-Request-Id}.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    /** Log dos erros inesperados. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalErrorHandler.class);

    /**
     * Converte qualquer erro em resposta JSON.
     *
     * @param error o erro que escapou da rota
     * @return a resposta de erro, ou nula quando o cliente já fechou a conexão
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorBody> handle(Throwable error) {

        // ── 1. Decide status e mensagem.
        ApiError api = ErrorClassifier.classify(error);

        // ── 2. Cliente foi embora (ex.: download abortado, G30): nada a responder, nada a alertar.
        if (!api.hasResponse()) {
            LOG.debug("Cliente fechou a conexão antes do fim da resposta: {}", error.toString());
            return null;
        }

        // ── 3. Falha do servidor: registra com o requestId para achar depois.
        if (api.unexpected()) {
            LOG.error("Erro inesperado (requestId={})", RequestIdFilter.current().orElse("-"), error);
        }

        // ── 4. Responde o JSON de erro.
        return ResponseEntity.status(api.status())
                .headers(api.headers())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(api.message()));
    }
}
