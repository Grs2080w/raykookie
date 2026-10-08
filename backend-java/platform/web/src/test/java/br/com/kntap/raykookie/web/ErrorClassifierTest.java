package br.com.kntap.raykookie.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.web.ErrorClassifier.ApiError;
import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Casos do classificador de erros difíceis de provocar pela rede.
 */
class ErrorClassifierTest {

    /**
     * Imitação do erro do Tomcat quando o cliente fecha a conexão (o classificador reconhece pelo nome).
     */
    static final class ClientAbortException extends IOException {

        /** Versão de serialização. */
        private static final long serialVersionUID = 1L;
    }

    /**
     * Cliente que abortou o download: sem resposta e sem alerta (G30).
     */
    @Test
    @DisplayName("cliente que fechou a conexão: nada a responder, não é falha do servidor")
    void clientAbortIsNotAFailure() {

        // ── 1. Erro de escrita causado pelo cliente que foi embora.
        ApiError api = ErrorClassifier.classify(new IllegalStateException("escrita", new ClientAbortException()));

        // ── 2. Nem resposta, nem log de erro.
        assertThat(api.hasResponse()).isFalse();
        assertThat(api.unexpected()).isFalse();
    }

    /**
     * Consulta cancelada pelo statement_timeout, traduzida ou não pelo Spring: 503.
     */
    @Test
    @DisplayName("statement_timeout: 503, não 500")
    void statementTimeoutIsUnavailable() {

        // ── 1. As duas formas em que o cancelamento chega.
        ApiError translated = ErrorClassifier.classify(new QueryTimeoutException("cancelada"));
        ApiError raw = ErrorClassifier.classify(
                new UncategorizedSQLException("consulta", "SELECT 1", new SQLException("cancelada", "57014")));

        // ── 2. Banco travado é indisponibilidade, sem alerta de erro inesperado.
        assertThat(translated.status()).isEqualTo(503);
        assertThat(raw.status()).isEqualTo(503);
        assertThat(raw.unexpected()).isFalse();
    }

    /**
     * Erro do Spring com status próprio: a mensagem genérica desse status.
     *
     * @param status  status do erro
     * @param message mensagem esperada
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "401 | Sessão expirada. Entre novamente.",
        "403 | Acesso negado.",
        "409 | Não foi possível concluir agora. Atualize e tente de novo.",
        "415 | Tipo de conteúdo não suportado.",
        "429 | Muitas tentativas. Aguarde um pouco e tente de novo.",
        "418 | Dados inválidos.",
        "502 | Serviço indisponível. Tente de novo em instantes.",
        "501 | Algo deu errado no servidor. Tente de novo.",
    })
    @DisplayName("erro do Spring com status: mensagem genérica do status")
    void statusFromSpringError(int status, String message) {

        // ── 1. Erro que já sabe o próprio status.
        ApiError api = ErrorClassifier.classify(new ResponseStatusException(HttpStatus.valueOf(status)));

        // ── 2. Status mantido, mensagem em português.
        assertThat(api.status()).isEqualTo(status);
        assertThat(api.message()).isEqualTo(message);
    }

    /**
     * Mensagem com aspas e controle não quebra o JSON.
     */
    @Test
    @DisplayName("ErrorBody escapa aspas, barras e caracteres de controle")
    void errorBodyEscapes() {
        assertThat(new ErrorBody("a\"b\\c\nd\u0001").toJson()).isEqualTo("{\"error\":\"a\\\"b\\\\c\\nd\\u0001\"}");
    }
}
