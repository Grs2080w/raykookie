package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.kernel.DomainException;
import jakarta.validation.ConstraintViolation;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Decide o status HTTP e a mensagem de cada erro que escapa de uma rota (G20). Sem estado e sem
 * Spring MVC: o {@link GlobalErrorHandler} só aplica o resultado.
 */
public final class ErrorClassifier {

    /** Nome da exceção do Tomcat para um arquivo de multipart acima de {@code max-file-size}. */
    private static final String FILE_SIZE_LIMIT = "FileSizeLimitExceededException";

    /** Nome da exceção do Tomcat para o cliente que fechou a conexão no meio da resposta. */
    private static final String CLIENT_ABORT = "ClientAbortException";

    /** Nome da exceção do Spring para resposta que não pode mais ser escrita (cliente foi embora). */
    private static final String RESPONSE_NOT_USABLE = "AsyncRequestNotUsableException";

    /** Código SQL de "consulta cancelada" (statement_timeout). */
    private static final String QUERY_CANCELED = "57014";

    /**
     * Sem instâncias: só a função de classificação.
     */
    private ErrorClassifier() {
    }

    /**
     * Classifica um erro.
     *
     * @param error o erro que escapou da rota
     * @return status, mensagem, cabeçalhos e como registrar
     */
    public static ApiError classify(Throwable error) {

        // ── 1. Erros que dependem da causa, não do tipo de fora: corpo grande demais e cliente que foi embora.
        Optional<RequestBodyTooLargeException> tooLarge = causeOf(error, RequestBodyTooLargeException.class);
        if (tooLarge.isPresent()) {
            return ApiError.of(413, tooLarge.get().getMessage());
        }
        if (hasCauseNamed(error, CLIENT_ABORT) || hasCauseNamed(error, RESPONSE_NOT_USABLE)) {
            return ApiError.clientGone();
        }

        // ── 2. Regra de negócio: o próprio erro traz status e mensagem.
        if (error instanceof DomainException domain) {
            return ApiError.of(domain.httpStatus(), domain.getMessage());
        }

        // ── 3. Erros do banco: as mesmas mensagens do Node para cada código SQL.
        Optional<ApiError> database = classifyDatabase(error);
        if (database.isPresent()) {
            return database.get();
        }

        // ── 4. Erros da borda HTTP (validação, rota, método, multipart).
        Optional<ApiError> web = classifyWeb(error);
        return web.orElseGet(ApiError::serverFailure);
    }

    /**
     * Erros vindos do banco.
     *
     * @param error o erro
     * @return a classificação, ou vazio se não for erro de banco conhecido
     */
    private static Optional<ApiError> classifyDatabase(Throwable error) {

        // ── 1. Sem conexão (banco fora ou pool esgotado) ou consulta cancelada pelo statement_timeout: 503.
        if (error instanceof CannotGetJdbcConnectionException || error instanceof QueryTimeoutException
                || QUERY_CANCELED.equals(sqlState(error))) {
            return Optional.of(ApiError.of(503, ErrorMessages.UNAVAILABLE));
        }
        if (!(error instanceof DataIntegrityViolationException)) {
            return Optional.empty();
        }

        // ── 2. Violação de restrição: mensagem pelo código SQL, como em backend/lib/api.ts.
        return switch (String.valueOf(sqlState(error))) {
            case "22003" -> Optional.of(ApiError.of(400, ErrorMessages.NUMBER_TOO_LARGE));
            case "23505" -> Optional.of(ApiError.of(409, ErrorMessages.DUPLICATE));
            case "23503" -> Optional.of(ApiError.of(409, ErrorMessages.IN_USE));
            case "23514" -> Optional.of(ApiError.of(400, ErrorMessages.OUT_OF_RANGE));
            default -> Optional.empty();
        };
    }

    /**
     * Erros da borda HTTP.
     *
     * @param error o erro
     * @return a classificação, ou vazio se não for um erro da borda
     */
    private static Optional<ApiError> classifyWeb(Throwable error) {

        // ── 1. Multipart acima do limite: por arquivo ou pelo envio inteiro.
        if (error instanceof MaxUploadSizeExceededException) {
            String message = hasCauseNamed(error, FILE_SIZE_LIMIT)
                    ? ErrorMessages.PHOTO_TOO_LARGE
                    : ErrorMessages.FORM_TOO_LARGE;
            return Optional.of(ApiError.of(413, message));
        }

        // ── 2. Campo inválido: a mensagem do primeiro campo, se a validação tiver uma em português.
        if (error instanceof BindException bind) {
            return Optional.of(ApiError.of(400, firstFieldMessage(bind)));
        }
        if (error instanceof HttpMessageNotReadableException || error instanceof TypeMismatchException
                || error instanceof MultipartException) {
            return Optional.of(ApiError.of(400, ErrorMessages.INVALID_INPUT));
        }

        // ── 3. Rota inexistente e método errado (este mantém o cabeçalho Allow).
        if (error instanceof NoResourceFoundException) {
            return Optional.of(ApiError.of(404, ErrorMessages.NOT_FOUND));
        }
        if (error instanceof HttpRequestMethodNotSupportedException method) {
            return Optional.of(new ApiError(405, ErrorMessages.METHOD_NOT_ALLOWED, method.getHeaders(), false));
        }

        // ── 4. Qualquer outro erro do Spring MVC sabe o próprio status; a mensagem vem da tabela.
        if (error instanceof ErrorResponse response) {
            int status = response.getStatusCode().value();
            return Optional.of(new ApiError(status, ErrorMessages.forStatus(status), response.getHeaders(),
                    status >= 500));
        }
        return Optional.empty();
    }

    /**
     * Mensagem do primeiro campo inválido. Só vale quando a anotação de validação traz mensagem própria
     * (em português); a mensagem padrão do Jakarta Validation viria em inglês.
     *
     * @param bind erros de validação
     * @return a mensagem do campo, ou {@link ErrorMessages#INVALID_INPUT}
     */
    private static String firstFieldMessage(BindException bind) {
        FieldError field = bind.getFieldError();
        if (field == null || field.getDefaultMessage() == null || !field.contains(ConstraintViolation.class)) {
            return ErrorMessages.INVALID_INPUT;
        }
        String template = field.unwrap(ConstraintViolation.class).getMessageTemplate();
        return template.startsWith("{") ? ErrorMessages.INVALID_INPUT : field.getDefaultMessage();
    }

    /**
     * Procura na cadeia de causas um erro de um tipo.
     *
     * @param error o erro
     * @param type  o tipo procurado
     * @param <T>   o tipo procurado
     * @return o primeiro erro do tipo na cadeia
     */
    private static <T extends Throwable> Optional<T> causeOf(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return Optional.of(type.cast(cause));
            }
        }
        return Optional.empty();
    }

    /**
     * Diz se a cadeia de causas tem um erro com este nome simples (para tipos do Tomcat, que este módulo
     * não importa).
     *
     * @param error o erro
     * @param name  nome simples da classe
     * @return verdadeiro se algum erro da cadeia tem esse nome
     */
    private static boolean hasCauseNamed(Throwable error, String name) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Código SQL ({@code SQLState}) do primeiro {@link SQLException} da cadeia.
     *
     * @param error o erro
     * @return o código, ou nulo se não houver
     */
    private static String sqlState(Throwable error) {
        return causeOf(error, SQLException.class).map(SQLException::getSQLState).orElse(null);
    }

    /**
     * Resultado da classificação.
     *
     * @param status     status HTTP; 0 quando não há resposta a dar (o cliente foi embora)
     * @param message    mensagem em português
     * @param headers    cabeçalhos a devolver (ex.: {@code Allow} no 405)
     * @param unexpected verdadeiro se é falha do servidor, a registrar como {@code ERROR}
     */
    public record ApiError(int status, String message, HttpHeaders headers, boolean unexpected) {

        /**
         * Erro esperado, sem cabeçalhos extras.
         *
         * @param status  status HTTP
         * @param message mensagem em português
         * @return o erro
         */
        static ApiError of(int status, String message) {
            return new ApiError(status, message, HttpHeaders.EMPTY, status >= 500 && status != 503);
        }

        /**
         * Falha inesperada: 500 com a mensagem genérica, registrada como {@code ERROR}.
         *
         * @return o erro
         */
        static ApiError serverFailure() {
            return new ApiError(500, ErrorMessages.SERVER_ERROR, HttpHeaders.EMPTY, true);
        }

        /**
         * O cliente fechou a conexão: não há a quem responder, e não é falha do servidor (G30).
         *
         * @return o erro, com status 0
         */
        static ApiError clientGone() {
            return new ApiError(0, "", HttpHeaders.EMPTY, false);
        }

        /**
         * Diz se o cliente ainda espera uma resposta.
         *
         * @return falso quando o cliente já foi embora
         */
        public boolean hasResponse() {
            return status > 0;
        }
    }
}
