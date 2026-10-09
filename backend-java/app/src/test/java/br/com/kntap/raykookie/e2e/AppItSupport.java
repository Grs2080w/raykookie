package br.com.kntap.raykookie.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import br.com.kntap.raykookie.security.SessionTokens;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de ponta a ponta da aplicação inteira (Tarefa 7.1): todos os módulos juntos, o Mercado Pago falso
 * (que aceita as credenciais na subida, então o cartão fica ligado), o Firebase fora do ar, uma pasta de downloads e
 * as sessões da dona, de outra conta do painel e de clientes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.jobs-enabled=false", "rayka.payments-card-enabled=true",
            "rayka.mp.access-token=TEST-ponta-a-ponta", "rayka.mp.public-key=TEST-chave-publica",
            "rayka.mp.webhook-secret=segredo-ponta-a-ponta", "rayka.google.client-id=e2e.apps.googleusercontent.com",
            "rayka.google.client-secret=segredo-google-e2e"})
@ActiveProfiles("test")
abstract class AppItSupport extends PostgresTestBase {

    /** A dona da loja dos testes do app. */
    static final String OWNER = "dona-teste";

    /** Uma conta do painel que não é a dona (G01). */
    static final String STRANGER = "outra-conta";

    /** Senha da dona. */
    static final String PASSWORD = "senha-de-teste-123";

    /** Hash scrypt de {@link #PASSWORD} (o mesmo dos testes dos módulos). */
    static final String PASSWORD_HASH = "scrypt$00112233445566778899aabbccddeeff$"
            + "216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a4"
            + "2dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff";

    /** CPF válido de teste. */
    static final String CPF = "529.982.247-25";

    /** Mercado Pago falso. */
    static final WireMockServer MP = new WireMockServer(wireMockConfig().dynamicPort());

    /** Firebase fora do ar: toda conexão cai (troca de token e envio). */
    static final WireMockServer FIREBASE = new WireMockServer(wireMockConfig().dynamicPort());

    /** Pasta de downloads com o manifesto. */
    static final Path DOWNLOADS = downloads();

    static {
        MP.start();
        FIREBASE.start();
        MP.stubFor(get(urlPathEqualTo("/users/me")).willReturn(json("{\"id\":1}")));
        MP.stubFor(get(urlPathEqualTo("/v1/payment_methods")).willReturn(json("[]")));
        FIREBASE.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /** Conta de serviço do Firebase apontando para o falso. */
    static final Path SERVICE_ACCOUNT = serviceAccount();

    /** Porta do servidor. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Aponta o Mercado Pago, o Firebase e os downloads para os falsos.
     *
     * @param registry propriedades
     */
    @DynamicPropertySource
    static void fakes(DynamicPropertyRegistry registry) {
        registry.add("rayka.mp.api-base", MP::baseUrl);
        registry.add("rayka.fcm.base-url", FIREBASE::baseUrl);
        registry.add("rayka.firebase-service-account-path", SERVICE_ACCOUNT::toString);
        registry.add("rayka.downloads-dir", DOWNLOADS::toString);
    }

    /**
     * Limpa os limites, dá à dona uma senha conhecida e garante a outra conta do painel.
     */
    @BeforeEach
    void accounts() {
        MP.resetRequests();
        jdbc.sql("DELETE FROM rate_limits").update();
        jdbc.sql("UPDATE users SET password_hash = :h WHERE username = :u").param("h", PASSWORD_HASH)
                .param("u", OWNER).update();
        jdbc.sql("INSERT INTO users (username, password_hash) VALUES (:u, :h) ON CONFLICT DO NOTHING")
                .param("u", STRANGER).param("h", PASSWORD_HASH).update();
    }

    /**
     * Sessão de uma conta do painel, gravada direto no banco (a outra conta não consegue entrar pelo login).
     *
     * @param username conta
     * @return o token
     */
    String panelSession(String username) {
        String token = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO sessions (user_id, token_hash, expires_at)
                SELECT id, :hash, now() + interval '1 day' FROM users WHERE username = :u""")
                .param("hash", SessionTokens.hash(token)).param("u", username).update();
        return token;
    }

    /**
     * Cliente novo com sessão, gravado direto no banco.
     *
     * @return o id do cliente e o token
     */
    Customer newCustomer() {
        String token = UUID.randomUUID().toString();
        long id = jdbc.sql("INSERT INTO customers (email, name, password_hash) VALUES (:e, 'Cliente E2E', :h) "
                + "RETURNING id").param("e", "e2e-" + token.substring(0, 8) + "@teste.com").param("h", PASSWORD_HASH)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO customer_sessions (token_hash, customer_id, expires_at) "
                + "VALUES (:hash, :c, now() + interval '1 day')").param("hash", SessionTokens.hash(token))
                .param("c", id).update();
        return new Customer(id, token);
    }

    /**
     * Uma receita a R$ 9,50 no cardápio da dona.
     *
     * @return o id
     */
    String menuItem() {

        // ── 1. A receita.
        long recipe = jdbc.sql("""
                INSERT INTO recipes (user_id, name, sale_price)
                VALUES ((SELECT id FROM users WHERE username = :u), :name, 9.5) RETURNING id""")
                .param("u", OWNER).param("name", "Cookie E2E " + UUID.randomUUID().toString().substring(0, 6))
                .query(Long.class).single();

        // ── 2. Um ingrediente, para ter custo.
        long flour = jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES ((SELECT id FROM users WHERE username = :u), :name, 'g', 1000, 10) RETURNING id""")
                .param("u", OWNER).param("name", "Farinha " + recipe).query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 50)")
                .param("r", recipe).param("i", flour).update();
        return String.valueOf(recipe);
    }

    /**
     * Pedido para depois de amanhã: 2 unidades do item.
     *
     * @param item    item do cardápio
     * @param payment forma de pagamento
     * @return o corpo
     */
    static Map<String, Object> order(String item, String payment) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("id", item, "qty", 2)));
        body.put("contact_name", "Maria Souza");
        body.put("phone", "11912345678");
        body.put("fulfillment", "retirada");
        body.put("scheduled_for", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString());
        body.put("time_window", "tarde");
        body.put("payment_method", payment);
        return body;
    }

    /**
     * Pedido com cartão.
     *
     * @param item  item do cardápio
     * @param token token do cartão (o falso decide pelo token)
     * @return o corpo
     */
    static Map<String, Object> cardOrder(String item, String token) {
        Map<String, Object> body = order(item, "cartao");
        body.putAll(card(token));
        return body;
    }

    /**
     * Dados do cartão.
     *
     * @param token token
     * @return os campos
     */
    static Map<String, Object> card(String token) {
        return Map.of("card_token", token, "card_payment_method_id", "master", "cpf", CPF, "installments", 1,
                "card_issuer_id", "24");
    }

    /**
     * O falso responde o cartão com este token assim, depois do atraso.
     *
     * @param token   token do cartão
     * @param status  {@code approved}, {@code rejected}…
     * @param detail  {@code status_detail}
     * @param delayMs atraso
     */
    static void cardAnswer(String token, String status, String detail, int delayMs) {
        MP.stubFor(post(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.token", equalTo(token)))
                .willReturn(json("{\"id\":" + paymentId() + ",\"status\":\"" + status + "\",\"status_detail\":\""
                        + detail + "\",\"transaction_amount\":19.0}").withFixedDelay(delayMs)));
    }

    /**
     * Todo Pix criado devolve um código novo.
     */
    static void pixAnswers() {
        long id = paymentId();
        MP.stubFor(post(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.payment_method_id",
                equalTo("pix"))).willReturn(json("{\"id\":" + id + ",\"status\":\"pending\","
                        + "\"point_of_interaction\":{\"transaction_data\":{\"qr_code\":\"pix-" + id + "\","
                        + "\"qr_code_base64\":\"iVBOR\"}}}")));
    }

    /**
     * Id de pagamento novo.
     *
     * @return o id
     */
    static long paymentId() {
        return ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    /**
     * Resposta JSON.
     *
     * @param body corpo
     * @return a resposta
     */
    static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /**
     * Requisição JSON.
     *
     * @return a requisição
     */
    RequestSpecification api() {
        return RestAssured.given().port(port).contentType(ContentType.JSON).redirects().follow(false);
    }

    /**
     * Requisição com sessão.
     *
     * @param token token
     * @return a requisição
     */
    RequestSpecification as(String token) {
        return api().header("Authorization", "Bearer " + token);
    }

    /**
     * Login da dona pelo app.
     *
     * @param password senha
     * @param forwarded {@code X-Forwarded-For}
     * @return a resposta
     */
    Response login(String password, String forwarded) {
        return api().header("x-client", "mobile").header("X-Forwarded-For", forwarded)
                .body(Map.of("username", OWNER, "password", password)).post("/api/auth/login");
    }

    /**
     * Pasta de downloads com o APK do cliente e o de gestão.
     *
     * @return a pasta
     */
    private static Path downloads() {
        try {
            Path dir = Files.createTempDirectory("downloads-e2e");
            Files.write(dir.resolve("raykookie-1.0.0+3.apk"), new byte[] {1, 2, 3, 4});
            Files.write(dir.resolve("gestao-1.0.0+1.apk"), new byte[] {5, 6, 7});
            Files.writeString(dir.resolve("manifest.json"), """
                    {"client":{"app":"client","name":"raykookie","version":"1.0.0","build":3,
                               "file":"raykookie-1.0.0+3.apk","size":4,"sha256":"x","date":"2026-10-01"},
                     "server":{"app":"server","name":"gestão","version":"1.0.0","build":1,
                               "file":"gestao-1.0.0+1.apk","size":3,"sha256":"y","date":"2026-10-01"}}""");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Conta de serviço com chave RSA de verdade e o {@code token_uri} no Firebase fora do ar.
     *
     * @return o arquivo
     */
    private static Path serviceAccount() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\\n-----END PRIVATE KEY-----\\n";
            Path file = Files.createTempFile("firebase-e2e", ".json");
            Files.writeString(file, "{\"project_id\":\"rayka-e2e\",\"client_email\":\"push@rayka-e2e.iam\","
                    + "\"token_uri\":\"" + FIREBASE.baseUrl() + "/token\",\"private_key\":\"" + pem + "\"}");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Cliente com sessão.
     *
     * @param id    id
     * @param token token
     */
    record Customer(long id, String token) {
    }
}
