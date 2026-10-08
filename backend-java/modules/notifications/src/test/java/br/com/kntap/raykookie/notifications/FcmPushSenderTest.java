package br.com.kntap.raykookie.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.notifications.adapter.out.fcm.FcmPushSender;
import br.com.kntap.raykookie.notifications.application.ports.PushSender.PushResult;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import br.com.kntap.raykookie.testing.ManualClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Envio pelo FCM v1 com o Google e o FCM falsos, usando os corpos de erro documentados do FCM v1.
 */
class FcmPushSenderTest {

    /** Corpo real do FCM v1 para token que não existe mais. */
    private static final String UNREGISTERED = "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
            + "\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
            + "\"errorCode\":\"UNREGISTERED\"}]}}";

    /** Corpo real do FCM v1 para argumento inválido. */
    private static final String INVALID = "{\"error\":{\"code\":400,\"message\":\"The registration token is not a valid "
            + "FCM registration token\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":"
            + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}";

    /** Google (token OAuth) e FCM falsos. */
    private WireMockServer google;

    /** Pasta temporária para a conta de serviço. */
    @TempDir
    Path dir;

    /**
     * Sobe o falso com a troca de token aceitando.
     */
    @BeforeEach
    void start() {
        google = new WireMockServer(wireMockConfig().dynamicPort());
        google.start();
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"access_token\":\"ya29.teste\",\"expires_in\":3600}")));
    }

    /**
     * Para o falso.
     */
    @AfterEach
    void stop() {
        google.stop();
    }

    /**
     * Escreve uma conta de serviço com chave RSA de verdade apontando o token_uri para o falso.
     *
     * @param file arquivo
     * @throws Exception se a geração falhar
     */
    private void writeServiceAccount(Path file) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(file, "{\"project_id\":\"rayka-teste\",\"client_email\":\"push@rayka-teste.iam\","
                + "\"token_uri\":\"" + google.baseUrl() + "/token\",\"private_key\":" + quote(pem) + "}");
    }

    /**
     * Texto como string JSON.
     *
     * @param text texto
     * @return entre aspas e escapado
     */
    private static String quote(String text) {
        return "\"" + text.replace("\n", "\\n").replace("\r", "") + "\"";
    }

    /**
     * Uma mensagem para um token.
     *
     * @param token token
     * @return a mensagem
     */
    private static PushMessage message(String token) {
        return new PushMessage(token, "Título", "Texto", Map.of("type", "novo_pedido", "orderId", "7"),
                "pedido_sino", "pedido_sino");
    }

    /**
     * Entregue, token morto (UNREGISTERED), argumento inválido e indisponível que volta na nova tentativa.
     *
     * @throws Exception se a preparação falhar
     */
    @Test
    @DisplayName("FCM v1: entregue, UNREGISTERED morto, INVALID_ARGUMENT sem repetir, 503 repetido")
    void sendResults() throws Exception {

        // ── 1. Respostas por token.
        Path account = dir.resolve("conta.json");
        writeServiceAccount(account);
        String send = "/v1/projects/rayka-teste/messages:send";
        google.stubFor(post(urlPathEqualTo(send)).withRequestBody(matchingJsonPath("$.message.token", equalTo("ok")))
                .willReturn(aResponse().withBody("{\"name\":\"projects/rayka-teste/messages/1\"}")));
        google.stubFor(post(urlPathEqualTo(send)).withRequestBody(matchingJsonPath("$.message.token", equalTo("morto")))
                .willReturn(aResponse().withStatus(404).withBody(UNREGISTERED)));
        google.stubFor(post(urlPathEqualTo(send)).withRequestBody(matchingJsonPath("$.message.token", equalTo("ruim")))
                .willReturn(aResponse().withStatus(400).withBody(INVALID)));
        google.stubFor(post(urlPathEqualTo(send)).inScenario("instavel").whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$.message.token", equalTo("instavel")))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"status\":\"UNAVAILABLE\"}}"))
                .willSetStateTo("voltou"));
        google.stubFor(post(urlPathEqualTo(send)).inScenario("instavel").whenScenarioStateIs("voltou")
                .withRequestBody(matchingJsonPath("$.message.token", equalTo("instavel")))
                .willReturn(aResponse().withBody("{\"name\":\"projects/rayka-teste/messages/2\"}")));

        // ── 2. Manda as quatro.
        FcmPushSender sender = new FcmPushSender(account, google.baseUrl(), new ManualClock(Instant.now()));
        List<PushResult> results = sender.send(List.of(message("ok"), message("morto"), message("ruim"),
                message("instavel"))).orElseThrow();

        // ── 3. Cada uma com o desfecho certo; o corpo segue o formato do FCM v1.
        assertThat(results).containsExactly(new PushResult("ok", true, false), new PushResult("morto", false, true),
                new PushResult("ruim", false, false), new PushResult("instavel", true, false));
        google.verify(postRequestedFor(urlPathEqualTo(send))
                .withHeader("Authorization", equalTo("Bearer ya29.teste"))
                .withRequestBody(matchingJsonPath("$.message.android.notification.channel_id", equalTo("pedido_sino")))
                .withRequestBody(matchingJsonPath("$.message.android.priority", equalTo("high"))));
    }

    /**
     * Firebase fora na primeira tentativa: o aviso seguinte, 10 minutos depois, sai (G17).
     *
     * @throws Exception se a preparação falhar
     */
    @Test
    @DisplayName("Firebase indisponível na 1ª tentativa e disponível 10 min depois: o 2º aviso sai")
    void retriesInitializationAfterTenMinutes() throws Exception {

        // ── 1. A conta de serviço ainda não existe: a primeira tentativa falha.
        Path account = dir.resolve("depois.json");
        ManualClock clock = new ManualClock(Instant.parse("2026-10-08T12:00:00Z"));
        FcmPushSender sender = new FcmPushSender(account, google.baseUrl(), clock);
        google.stubFor(post(urlPathEqualTo("/v1/projects/rayka-teste/messages:send"))
                .willReturn(aResponse().withBody("{\"name\":\"x\"}")));
        assertThat(sender.send(List.of(message("ok")))).isEmpty();

        // ── 2. O arquivo aparece, mas antes dos 10 minutos ninguém tenta de novo.
        writeServiceAccount(account);
        clock.advance(Duration.ofMinutes(9));
        assertThat(sender.send(List.of(message("ok")))).isEmpty();

        // ── 3. Passados 10 minutos, inicializa e manda.
        clock.advance(Duration.ofMinutes(1));
        assertThat(sender.send(List.of(message("ok")))).hasValueSatisfying(r -> assertThat(r.get(0).ok()).isTrue());
    }
}
