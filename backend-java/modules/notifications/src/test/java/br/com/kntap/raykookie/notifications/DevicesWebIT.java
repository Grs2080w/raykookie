package br.com.kntap.raykookie.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Aparelhos pela rede e avisos gravados no arquivo do {@code PUSH_LOG_PATH} (como os testes do Node).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "rayka.push-log-path=target/push-log-teste.jsonl",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class DevicesWebIT extends PostgresTestBase {

    /** Arquivo dos avisos. */
    private static final Path LOG = Path.of("target/push-log-teste.jsonl");

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Eventos. */
    @Autowired
    ApplicationEventPublisher events;

    /**
     * Requisição JSON com um token.
     *
     * @param token token da sessão
     * @return a requisição
     */
    private RequestSpecification as(String token) {
        return RestAssured.given().port(port).contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    }

    /**
     * Entra como a dona e como a Ana.
     *
     * @param path rota de login
     * @param body corpo
     * @return o token
     */
    private String login(String path, Map<String, Object> body) {
        return RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile").body(body)
                .post(path).jsonPath().getString("token");
    }

    /**
     * Cadastro e descadastro pela rede; avisos para os aparelhos cadastrados vão para o arquivo.
     *
     * @throws Exception se a leitura falhar
     */
    @Test
    @DisplayName("aparelhos cadastrados recebem os avisos (arquivo do PUSH_LOG_PATH)")
    void devicesReceiveNotices() throws Exception {

        // ── 1. Um aparelho da dona (som caixa) e um da Ana.
        Files.deleteIfExists(LOG);
        String owner = login("/api/auth/login", Map.of("username", "dona", "password", "senha-de-teste-123"));
        String ana = login("/api/cliente/login", Map.of("email", "ana@teste.com", "password", "senha-de-teste-123"));
        assertThat(as(owner).body(Map.of("token", "aparelho-da-dona-0123456789", "sound", "caixa"))
                .post("/api/devices").statusCode()).isEqualTo(200);
        assertThat(as(owner).body(Map.of("token", "x")).post("/api/devices").statusCode()).isEqualTo(400);
        assertThat(as(owner).body(Map.of("token", "aparelho-da-dona-0123456789", "sound", "y"))
                .post("/api/devices").statusCode()).isEqualTo(400);
        assertThat(as(ana).body(Map.of("token", "aparelho-da-ana-0123456789")).post("/api/cliente/devices")
                .statusCode()).isEqualTo(200);

        // ── 2. Avisos fora de transação saem na hora (em segundo plano).
        long ownerId = jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single();
        long anaId = jdbc.sql("SELECT id FROM customers WHERE email = 'ana@teste.com'").query(Long.class).single();
        events.publishEvent(new OwnerNotice.Paid(new OwnerId(ownerId), 7, 1234, "Ana", Money.ofCents(4550), 2));
        events.publishEvent(new CustomerNotice.Status(new CustomerNotice.OrderRef(7, 1234, new CustomerId(anaId),
                Money.ofCents(4550), "retirada", "manha", false), "pronto"));
        List<String> lines = waitLines(2);

        // ── 3. Uma linha por aviso, no formato do Node.
        assertThat(String.join("\n", lines)).contains("\"title\":\"Pix recebido · Pedido #1234\"",
                "\"channelId\":\"pedido_caixa\"", "Pode retirar no endereço combinado, no período da manhã.");

        // ── 4. Descadastro.
        assertThat(as(owner).delete("/api/devices?token=aparelho-da-dona-0123456789").statusCode()).isEqualTo(200);
        assertThat(as(ana).delete("/api/cliente/devices?token=aparelho-da-ana-0123456789").statusCode())
                .isEqualTo(200);
        assertThat(as(ana).delete("/api/cliente/devices?token=x").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM customer_devices").query(Integer.class).single()).isZero();
    }

    /**
     * Espera as linhas do arquivo.
     *
     * @param count quantas
     * @return as linhas
     * @throws Exception se a leitura falhar
     */
    private static List<String> waitLines(int count) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (Files.exists(LOG) && Files.readAllLines(LOG).size() >= count) {
                return Files.readAllLines(LOG);
            }
            Thread.sleep(100);
        }
        return Files.exists(LOG) ? Files.readAllLines(LOG) : List.of();
    }
}
