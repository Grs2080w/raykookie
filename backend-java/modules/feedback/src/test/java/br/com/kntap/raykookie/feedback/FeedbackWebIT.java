package br.com.kntap.raykookie.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
 * Feedback pela rede, com o Postgres real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class FeedbackWebIT extends PostgresTestBase {

    /** PNG mínimo. */
    static final byte[] PNG = png();

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Token da dona. */
    String owner;

    /** Token do cliente. */
    String customer;

    /**
     * PNG com a assinatura.
     *
     * @return os bytes
     */
    private static byte[] png() {
        byte[] bytes = new byte[48];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, bytes, 0, 8);
        return bytes;
    }

    /**
     * Sessões.
     */
    @BeforeEach
    void sessions() {
        jdbc.sql("DELETE FROM rate_limits").update();
        owner = json().header("x-client", "mobile").body(Map.of("username", "dona", "password", "senha-de-teste-123"))
                .post("/api/auth/login").jsonPath().getString("token");
        customer = signUp();
    }

    /**
     * Cliente novo.
     *
     * @return o token
     */
    private String signUp() {
        return json().header("x-client", "mobile").body(Map.of("name", "Maria Souza", "email",
                "fb-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro").jsonPath().getString("token");
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    private RequestSpecification json() {
        return RestAssured.given().port(port).contentType(ContentType.JSON);
    }

    /**
     * Envia feedback.
     *
     * @param token  sessão
     * @param rating nota
     * @param photos fotos
     * @return a resposta
     */
    private Response send(String token, String rating, byte[]... photos) {
        var spec = RestAssured.given().port(port).header("Authorization", "Bearer " + token)
                .multiPart("rating", rating).multiPart("message", "  Muito   bom,\n adorei!  ");
        for (byte[] photo : photos) {
            spec = spec.multiPart("fotos", "f.png", photo, "image/png");
        }
        return spec.post("/api/cliente/feedback");
    }

    /**
     * Envio, lista e fotos.
     */
    @Test
    @DisplayName("envia com foto (201); a dona lista e vê a foto; o cliente vê a própria; de outro cliente 404")
    void submitAndRead() {

        // ── 1. Envia e a dona lista.
        Response made = send(customer, "5", PNG);
        assertThat(made.statusCode()).isEqualTo(201);
        long id = made.jsonPath().getLong("id");
        Response list = RestAssured.given().port(port).header("Authorization", "Bearer " + owner).get("/api/feedback");
        String path = "list.find { it.id == " + id + " }";
        assertThat(list.jsonPath().getInt(path + ".rating")).isEqualTo(5);
        assertThat(list.jsonPath().getString(path + ".message")).isEqualTo("Muito bom, adorei!");
        assertThat(list.jsonPath().getInt(path + ".photos")).isEqualTo(1);

        // ── 2. Fotos.
        Response photo = RestAssured.given().port(port).header("Authorization", "Bearer " + owner)
                .get("/api/feedback/" + id + "/fotos/1");
        assertThat(photo.getHeader("Content-Type")).isEqualTo("image/png");
        assertThat(photo.getHeader("Cache-Control")).isEqualTo("private, max-age=3600");
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + customer)
                .get("/api/cliente/feedback/" + id + "/fotos/1").statusCode()).isEqualTo(200);
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + signUp())
                .get("/api/cliente/feedback/" + id + "/fotos/1").statusCode()).isEqualTo(404);
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + owner)
                .get("/api/feedback/" + id + "/fotos/x").statusCode()).isEqualTo(404);
    }

    /**
     * Recusas.
     */
    @Test
    @DisplayName("recusas: 6 fotos 400; nota inválida 400; não-imagem 415; 2 MB + 1 byte 413; sem multipart 400")
    void refusals() {
        assertThat(send(customer, "5", PNG, PNG, PNG, PNG, PNG, PNG).statusCode()).isEqualTo(400);
        assertThat(send(customer, "3.5").statusCode()).isEqualTo(201);
        assertThat(send(customer, "7").jsonPath().getString("error")).isEqualTo("Escolha uma nota de 1 a 5 estrelas.");
        assertThat(send(customer, "", PNG).statusCode()).isEqualTo(400);
        assertThat(send(customer, "5", "texto".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(415);
        byte[] big = new byte[2_000_001];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(send(customer, "5", big).statusCode()).isEqualTo(413);
        assertThat(json().header("Authorization", "Bearer " + customer).body("{}").post("/api/cliente/feedback")
                .statusCode()).isEqualTo(400);
        assertThat(RestAssured.given().port(port).multiPart("rating", "5").post("/api/cliente/feedback").statusCode())
                .isEqualTo(401);
    }

    /**
     * Corpo de 13 MB.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("corpo de 13 MB: 413 pelo tamanho declarado, sem ler o corpo")
    void tooLarge() throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/cliente/feedback HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + customer
                    + "\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: 13631488\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String head = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            assertThat(head).isEqualTo("HTTP/1.1 413");
        }
    }
}
