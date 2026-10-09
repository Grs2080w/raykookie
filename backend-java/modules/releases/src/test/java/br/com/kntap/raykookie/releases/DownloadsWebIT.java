package br.com.kntap.raykookie.releases;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Downloads pela rede: lista, APK com {@code Range}, link assinado, app de gestão só para a dona e clientes que
 * fecham a conexão no meio (G30).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "rayka.download-secret=segredo-de-download-com-mais-de-32-caracteres",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class DownloadsWebIT extends PostgresTestBase {

    /** Tamanho do APK de teste (50 MB). */
    private static final int APK_SIZE = 50 * 1024 * 1024;

    /** Pasta dos downloads. */
    private static Path dir;

    /** sha256 do APK do cliente. */
    private static String sha;

    /** Porta. */
    @LocalServerPort
    int port;

    /**
     * Pasta temporária com os APKs e o manifesto.
     *
     * @return a pasta
     * @throws IOException se não der para gravar
     */
    private static synchronized Path dir() throws IOException {
        if (dir == null) {
            dir = Files.createTempDirectory("downloads-it");
            byte[] apk = new byte[APK_SIZE];
            new Random(7).nextBytes(apk);
            Files.write(dir.resolve("raykookie-1.0.0+3.apk"), apk);
            Files.write(dir.resolve("gestao-1.0.0+1.apk"), new byte[] {1, 2, 3});
            try {
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apk));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            Files.writeString(dir.resolve("manifest.json"), """
                    {"client":{"app":"client","name":"raykookie","version":"1.0.0","build":3,
                               "file":"raykookie-1.0.0+3.apk","size":%d,"sha256":"%s","date":"2026-10-01"},
                     "server":{"app":"server","name":"gestão","version":"1.0.0","build":1,
                               "file":"gestao-1.0.0+1.apk","size":3,"sha256":"y","date":"2026-10-01"}}"""
                    .formatted(APK_SIZE, sha));
        }
        return dir;
    }

    /**
     * Aponta a pasta.
     *
     * @param registry propriedades
     * @throws IOException se não der para gravar
     */
    @DynamicPropertySource
    static void downloads(DynamicPropertyRegistry registry) throws IOException {
        registry.add("rayka.downloads-dir", () -> {
            try {
                return dir().toString();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Cria a pasta antes do contexto.
     *
     * @throws IOException se não der para gravar
     */
    @BeforeAll
    static void prepare() throws IOException {
        dir();
    }

    /**
     * Sessão da dona.
     *
     * @return o token
     */
    private String owner() {
        return RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login")
                .jsonPath().getString("token");
    }

    /**
     * Lista, gestão e link assinado.
     */
    @Test
    @DisplayName("lista: cliente público; gestão só para a dona; link de 5 min; cliente não vê nem baixa a gestão")
    void listAndServer() {

        // ── 1. Sem sessão e com sessão de cliente: só o app do cliente.
        Response anonymous = RestAssured.given().port(port).get("/api/downloads");
        assertThat(anonymous.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(anonymous.jsonPath().getString("client.url")).isEqualTo("/api/downloads/client");
        assertThat(anonymous.jsonPath().getString("server")).isNull();
        String customer = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("name", "Cliente", "email", "dl-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@teste.com", "password", "senha-boa-123")).post("/api/cliente/cadastro").jsonPath()
                .getString("token");
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + customer).get("/api/downloads")
                .jsonPath().getString("server")).isNull();
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + customer)
                .get("/api/downloads/server").statusCode()).isEqualTo(401);

        // ── 2. A dona vê e baixa; o link assinado baixa sem sessão; vencido ou de outro app é 401.
        String owner = owner();
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + owner).get("/api/downloads")
                .jsonPath().getString("server.url")).isEqualTo("/api/downloads/server");
        String url = RestAssured.given().port(port).header("Authorization", "Bearer " + owner)
                .post("/api/downloads/server/link").jsonPath().getString("url");
        Response viaLink = RestAssured.given().port(port).urlEncodingEnabled(false).get(url);
        assertThat(viaLink.statusCode()).isEqualTo(200);
        assertThat(viaLink.getHeader("X-App-Version")).isEqualTo("1.0.0+1");
        assertThat(RestAssured.given().port(port).get("/api/downloads/server?t=1.abc").statusCode()).isEqualTo(401);
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + owner)
                .post("/api/downloads/client/link").jsonPath().getString("url")).isEqualTo("/api/downloads/client");
        assertThat(RestAssured.given().port(port).get("/api/downloads/outro").statusCode()).isEqualTo(404);
        assertThat(RestAssured.given().port(port).header("Authorization", "Bearer " + owner)
                .post("/api/downloads/outro/link").statusCode()).isEqualTo(404);
    }

    /**
     * Range.
     */
    @Test
    @DisplayName("APK: cabeçalhos, Range bytes=-10 ⇒ últimos 10 bytes (206), bytes=999999999- ⇒ 416")
    void ranges() throws IOException {
        Response tail = RestAssured.given().port(port).header("Range", "bytes=-10").get("/api/downloads/client");
        assertThat(tail.statusCode()).isEqualTo(206);
        assertThat(tail.getHeader("Content-Range")).isEqualTo("bytes " + (APK_SIZE - 10) + "-" + (APK_SIZE - 1) + "/"
                + APK_SIZE);
        byte[] all = Files.readAllBytes(dir().resolve("raykookie-1.0.0+3.apk"));
        assertThat(tail.asByteArray()).isEqualTo(java.util.Arrays.copyOfRange(all, APK_SIZE - 10, APK_SIZE));
        assertThat(tail.getHeader("ETag")).isEqualTo("\"" + sha + "\"");
        assertThat(tail.getHeader("Content-Type")).isEqualTo("application/vnd.android.package-archive");
        Response none = RestAssured.given().port(port).header("Range", "bytes=999999999-").get("/api/downloads/client");
        assertThat(none.statusCode()).isEqualTo(416);
        assertThat(none.getHeader("Content-Range")).isEqualTo("bytes */" + APK_SIZE);
    }

    /**
     * Clientes que desistem no meio (G30).
     *
     * @param output saída capturada
     * @throws Exception se uma conexão falhar de um jeito inesperado
     */
    @Test
    @DisplayName("G30: 20 clientes fecham depois de 1 MB: nenhum ERROR, saúde 200 e o download completo bate o sha256")
    void abortedDownloads(CapturedOutput output) throws Exception {

        // ── 1. 20 downloads abandonados depois de 1 MB.
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/api/downloads/client");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            runs.add(pool.submit(() -> {
                HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(uri).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    in.readNBytes(1024 * 1024);
                }
                return res.statusCode();
            }));
        }
        for (Future<Integer> run : runs) {
            assertThat(run.get()).isEqualTo(200);
        }
        pool.shutdown();

        // ── 2. O servidor segue bem e um download completo está certo.
        Thread.sleep(500);
        assertThat(RestAssured.given().port(port).get("/api/downloads").statusCode()).isEqualTo(200);
        byte[] whole = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(whole))).isEqualTo(sha);
        assertThat(output.getAll()).doesNotContain(" ERROR ");
    }
}
