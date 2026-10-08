package br.com.kntap.raykookie.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.web.RawHttp.Response;
import io.restassured.RestAssured;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Corpos grandes demais são recusados com 413 antes de serem lidos para a memória (G08).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestSizeLimitIT {

    /** Porta aleatória do servidor de teste. */
    @LocalServerPort
    int port;

    /**
     * Foto de 50 MB anunciada no Content-Length: recusada pelo cabeçalho, sem esperar o corpo chegar.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("Content-Length de 50 MB na foto: 413 em até 1 s, sem ler o corpo")
    void declaredLengthIsRejectedBeforeReading() throws IOException {

        // ── 1. Anuncia 50 MB mas manda só 1 KB: se o servidor tentasse ler tudo, ficaria esperando.
        Response response = RawHttp.exchange(port,
                "PUT /api/recipes/1/photo HTTP/1.1\r\nContent-Type: image/jpeg\r\nContent-Length: 52428800",
                RawHttp.fixed(1024));

        // ── 2. Resposta imediata, em JSON, com a mensagem do Node para foto grande.
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.elapsedMillis()).isLessThanOrEqualTo(1000);
        assertThat(response.headers().get("Content-Type")).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"error\":\"A foto é grande demais (máximo de 2 MB).\"}");
    }

    /**
     * Foto de 3 MB em partes (sem Content-Length): o contador para a leitura no limite de 2 MB.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("foto de 3 MB em partes, sem Content-Length: 413")
    void chunkedBodyIsCutAtTheLimit() throws IOException {

        // ── 1. Envia 3 MB em partes.
        Response response = RawHttp.exchange(port,
                "PUT /api/recipes/1/photo HTTP/1.1\r\nContent-Type: image/jpeg\r\nTransfer-Encoding: chunked",
                RawHttp.chunked(3L * 1024 * 1024));

        // ── 2. Recusada com a mensagem da rota.
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"error\":\"A foto é grande demais (máximo de 2 MB).\"}");
    }

    /**
     * JSON em partes acima do limite padrão de 1 MB: mesmo caminho, mensagem padrão.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("JSON de 2 MB em partes numa rota comum: 413 com a mensagem padrão")
    void chunkedJsonUsesTheDefaultLimit() throws IOException {

        // ── 1. Envia 2 MB em partes para uma rota JSON comum.
        Response response = RawHttp.exchange(port,
                "POST /api/echo HTTP/1.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked",
                RawHttp.chunkedJson(2L * 1024 * 1024));

        // ── 2. Recusada com a mensagem padrão.
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"error\":\"O envio é grande demais.\"}");
    }

    /**
     * JSON anunciado acima de 1 MB numa rota comum.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("Content-Length acima de 1 MB numa rota comum: 413")
    void declaredJsonAboveDefaultLimit() throws IOException {

        // ── 1. Anuncia 1 MB + 1 byte.
        Response response = RawHttp.exchange(port,
                "POST /api/echo HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 1048577",
                RawHttp.fixed(10));

        // ── 2. Recusada pelo cabeçalho.
        assertThat(response.status()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"error\":\"O envio é grande demais.\"}");
    }

    /**
     * Foto dentro do limite passa inteira, também quando enviada em partes.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("foto de 1,5 MB em partes passa inteira")
    void bodyWithinLimitIsRead() throws IOException {

        // ── 1. Envia 1,5 MB em partes.
        Response response = RawHttp.exchange(port,
                "PUT /api/recipes/7/photo HTTP/1.1\r\nContent-Type: image/jpeg\r\nTransfer-Encoding: chunked",
                RawHttp.chunked(1_500_000));

        // ── 2. A rota leu todos os bytes.
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"bytes\":1500000}");
    }

    /**
     * Multipart com uma foto acima de 2 MB: limite por arquivo, mensagem do Node.
     */
    @Test
    @DisplayName("multipart com foto de 3 MB: 413 com a mensagem do Node")
    void multipartFileAboveLimit() {

        // ── 1. Envia uma foto de 3 MB no formulário.
        var response = RestAssured.given().port(port)
                .multiPart("fotos", "foto.jpg", new byte[3 * 1024 * 1024], "image/jpeg")
                .post("/api/cliente/feedback");

        // ── 2. Recusada com a mensagem por foto.
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.jsonPath().getString("error")).isEqualTo(ErrorMessages.PHOTO_TOO_LARGE);
    }

    /**
     * Multipart dentro dos limites chega à rota.
     */
    @Test
    @DisplayName("multipart com duas fotos pequenas passa")
    void multipartWithinLimits() {

        // ── 1. Envia duas fotos pequenas.
        var response = RestAssured.given().port(port)
                .multiPart("fotos", "a.jpg", new byte[1000], "image/jpeg")
                .multiPart("fotos", "b.jpg", new byte[1000], "image/jpeg")
                .post("/api/cliente/feedback");

        // ── 2. As duas chegam.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("files")).isEqualTo(2);
    }
}
