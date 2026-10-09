package br.com.kntap.raykookie.notifications.adapter.out.fcm;

import br.com.kntap.raykookie.notifications.application.ports.PushSender;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envio pelo Firebase Cloud Messaging (API HTTP v1), por REST. O token OAuth sai da conta de serviço
 * ({@code FIREBASE_SERVICE_ACCOUNT_PATH}): um JWT assinado com a chave dela, trocado no {@code token_uri}.
 *
 * <p>Inicialização preguiçosa (G17): no Node, uma falha na primeira tentativa desligava o push até reiniciar
 * o servidor; aqui, depois de uma falha, tenta de novo passados 10 minutos.
 *
 * <p>Respostas de erro do FCM v1: {@code UNREGISTERED} = token morto (o aparelho é apagado);
 * {@code UNAVAILABLE}/{@code INTERNAL} (5xx) = nova tentativa com espera; o resto (ex.: {@code INVALID_ARGUMENT})
 * é registrado e não repete.
 */
public class FcmPushSender implements PushSender {

    /** Espera depois de uma inicialização que falhou (G17). */
    static final Duration RETRY_INIT_AFTER = Duration.ofMinutes(10);

    /** Escopo do token OAuth. */
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** Novas tentativas de uma mensagem com o FCM indisponível. */
    private static final int ATTEMPTS = 3;

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(FcmPushSender.class);

    /** Arquivo da conta de serviço. */
    private final Path serviceAccount;

    /** Base da API do FCM ({@code https://fcm.googleapis.com}). */
    private final String fcmBase;

    /** Relógio. */
    private final Clock clock;

    /** Cliente HTTP. */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** JSON. */
    private final JsonMapper json = JsonMapper.builder().build();

    /** Credencial carregada (nula até a primeira inicialização bem-sucedida). */
    private Account account;

    /** Quando a última inicialização falhou (nulo = nunca falhou). */
    private Instant failedAt;

    /** Token OAuth em cache. */
    private String accessToken;

    /** Validade do token em cache. */
    private Instant accessTokenExpires = Instant.EPOCH;

    /**
     * Cria o envio.
     *
     * @param serviceAccount arquivo JSON da conta de serviço
     * @param fcmBase        base da API do FCM
     * @param clock          relógio
     */
    public FcmPushSender(Path serviceAccount, String fcmBase, Clock clock) {
        this.serviceAccount = serviceAccount;
        this.fcmBase = fcmBase.replaceAll("/+$", "");
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<List<PushResult>> send(List<PushMessage> messages) {

        // ── 1. Credencial e token OAuth; indisponível = nada sai agora (nova tentativa em 10 min).
        Optional<String> token = accessToken();
        if (token.isEmpty()) {
            return Optional.empty();
        }

        // ── 2. Uma chamada por mensagem, como o sendEach do Admin SDK.
        List<PushResult> results = new ArrayList<>();
        for (PushMessage message : messages) {
            results.add(sendOne(token.get(), message));
        }
        return Optional.of(results);
    }

    /**
     * Manda uma mensagem, com novas tentativas se o FCM estiver indisponível.
     *
     * @param token   token OAuth
     * @param message mensagem
     * @return o resultado
     */
    private PushResult sendOne(String token, PushMessage message) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Reply reply = post(token, message);
            if (reply.status() == 200) {
                return new PushResult(message.token(), true, false);
            }
            String code = errorCode(reply.body());
            if ("UNREGISTERED".equals(code)) {
                return new PushResult(message.token(), false, true);
            }
            if (reply.status() < 500 && reply.status() != 0) {
                LOG.warn("FCM recusou a mensagem ({} {}): não repete", reply.status(), code);
                return new PushResult(message.token(), false, false);
            }
            pause(attempt);
        }
        LOG.warn("FCM indisponível depois de {} tentativas", ATTEMPTS);
        return new PushResult(message.token(), false, false);
    }

    /**
     * Chama o {@code messages:send}.
     *
     * @param token   token OAuth
     * @param message mensagem
     * @return status e corpo (status 0 se a rede falhou)
     */
    private Reply post(String token, PushMessage message) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(fcmBase + "/v1/projects/" + account.projectId() + "/messages:send"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message", body(message)))))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        } catch (IOException failure) {
            return new Reply(0, "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Reply(0, "");
        }
    }

    /**
     * A mensagem no formato do FCM v1 (como o Admin SDK monta a partir do formato do Node).
     *
     * @param message mensagem
     * @return o objeto {@code message}
     */
    static Map<String, Object> body(PushMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", message.token());
        body.put("notification", Map.of("title", message.title(), "body", message.body()));
        body.put("data", message.data());
        body.put("android", Map.of("priority", "high",
                "notification", Map.of("channel_id", message.channelId(), "sound", message.sound())));
        return body;
    }

    /**
     * {@code errorCode} do FCM nos detalhes do erro.
     *
     * @param body corpo da resposta
     * @return o código, ou o {@code status} do erro, ou vazio
     */
    private String errorCode(String body) {
        try {
            JsonNode error = json.readTree(body.isEmpty() ? "{}" : body).path("error");
            for (JsonNode detail : error.path("details")) {
                if (detail.has("errorCode")) {
                    return detail.path("errorCode").asString();
                }
            }
            return error.path("status").asString("");
        } catch (JacksonException notJson) {
            return "";
        }
    }

    /**
     * Espera antes de tentar de novo (200 ms, 400 ms…).
     *
     * @param attempt tentativa que acabou de falhar
     */
    private static void pause(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Token OAuth válido, inicializando a credencial se preciso.
     *
     * @return o token, ou vazio se a credencial não está disponível agora
     */
    private Optional<String> accessToken() {

        // ── 1. Credencial: carrega na primeira vez; depois de uma falha, só de novo após 10 minutos.
        Instant now = clock.instant();
        if (account == null) {
            if (failedAt != null && now.isBefore(failedAt.plus(RETRY_INIT_AFTER))) {
                return Optional.empty();
            }
            try {
                account = Account.read(json.readTree(Files.readString(serviceAccount)));
                failedAt = null;
            } catch (IOException | JacksonException | GeneralSecurityException | IllegalArgumentException failure) {
                failedAt = now;
                LOG.error("Firebase não inicializou; nova tentativa em 10 minutos: {}", failure.toString());
                return Optional.empty();
            }
        }

        // ── 2. Token em cache até 1 minuto antes de vencer.
        if (accessToken != null && now.isBefore(accessTokenExpires.minusSeconds(60))) {
            return Optional.of(accessToken);
        }
        return exchange(now);
    }

    /**
     * Troca um JWT assinado pela conta de serviço por um token OAuth.
     *
     * @param now agora
     * @return o token, ou vazio se a troca falhou (conta como falha de inicialização)
     */
    private Optional<String> exchange(Instant now) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(account.clientEmail()).audience(account.tokenUri())
                    .claim("scope", SCOPE).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
            jwt.sign(new RSASSASigner(account.privateKey()));
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                    StandardCharsets.UTF_8) + "&assertion=" + jwt.serialize();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(account.tokenUri()))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode token = json.readTree(response.body());
            if (response.statusCode() != 200 || token.path("access_token").asString("").isEmpty()) {
                throw new IOException("token OAuth recusado: " + response.statusCode());
            }
            accessToken = token.path("access_token").asString();
            accessTokenExpires = now.plusSeconds(token.path("expires_in").asLong(3600));
            return Optional.of(accessToken);
        } catch (IOException | JOSEException | JacksonException failure) {
            account = null;
            failedAt = now;
            LOG.error("Token do Firebase falhou; nova tentativa em 10 minutos: {}", failure.toString());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Resposta do FCM.
     *
     * @param status status HTTP (0 = rede)
     * @param body   corpo
     */
    private record Reply(int status, String body) {
    }

    /**
     * Conta de serviço do Firebase.
     *
     * @param projectId   projeto
     * @param clientEmail e-mail da conta
     * @param tokenUri    endereço da troca do token
     * @param privateKey  chave privada
     */
    record Account(String projectId, String clientEmail, String tokenUri, PrivateKey privateKey) {

        /**
         * Lê o JSON da conta de serviço.
         *
         * @param file o JSON
         * @return a conta
         * @throws GeneralSecurityException se a chave for inválida
         */
        static Account read(JsonNode file) throws GeneralSecurityException {

            // ── 1. Chave privada PKCS#8 do PEM.
            String pem = file.path("private_key").asString("");
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));

            // ── 2. Projeto e conta, obrigatórios.
            String projectId = file.path("project_id").asString("");
            String email = file.path("client_email").asString("");
            if (projectId.isEmpty() || email.isEmpty()) {
                throw new IllegalArgumentException("conta de serviço sem project_id ou client_email");
            }
            return new Account(projectId, email, file.path("token_uri").asString("https://oauth2.googleapis.com/token"),
                    key);
        }
    }
}
