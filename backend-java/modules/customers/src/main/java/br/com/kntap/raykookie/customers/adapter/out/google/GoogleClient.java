package br.com.kntap.raykookie.customers.adapter.out.google;

import br.com.kntap.raykookie.customers.application.ports.GoogleAccounts;
import br.com.kntap.raykookie.kernel.DomainException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Login com Google. O site usa o fluxo OAuth (troca do {@code code} por token e leitura do perfil), como o
 * Node. O app manda um {@code id_token}, que agora é conferido <b>localmente</b> (assinatura pela JWKS do
 * Google, emissor, público, validade e e-mail confirmado), em vez de perguntar ao endpoint {@code tokeninfo}
 * (G19). As mensagens de recusa são as do Node ({@code backend/lib/google.ts}). Os endereços do Google são
 * configuráveis para os testes apontarem para o WireMock.
 */
@Component
public class GoogleClient implements GoogleAccounts {

    /** Emissores aceitos do {@code id_token}. */
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    /** Tempo limite das chamadas ao Google. */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** {@code GOOGLE_CLIENT_ID}. */
    private final String clientId;

    /** {@code GOOGLE_CLIENT_SECRET}. */
    private final String clientSecret;

    /** {@code SITE_URL} sem a barra final. */
    private final String siteUrl;

    /** Endpoints do Google. */
    private final Endpoints endpoints;

    /** Relógio (validade do token). */
    private final Clock clock;

    /** Cliente HTTP. */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Leitor de JSON. */
    private final JsonMapper json = JsonMapper.builder().build();

    /** Conferência de assinatura pela JWKS (as chaves ficam em cache). */
    private final DefaultJWTProcessor<SecurityContext> processor;

    /**
     * Cria o cliente.
     *
     * @param clientId     {@code rayka.google.client-id}
     * @param clientSecret {@code rayka.google.client-secret}
     * @param siteUrl      {@code rayka.site-url}
     * @param endpoints    endereços do Google
     * @param clock        relógio
     * @throws MalformedURLException se o endereço da JWKS for inválido
     */
    public GoogleClient(@Value("${rayka.google.client-id:}") String clientId,
            @Value("${rayka.google.client-secret:}") String clientSecret,
            @Value("${rayka.site-url:https://raykookie.com.br}") String siteUrl, Endpoints endpoints, Clock clock)
            throws MalformedURLException {

        // ── 1. Configuração.
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        this.siteUrl = siteUrl.replaceAll("/+$", "");
        this.endpoints = endpoints;
        this.clock = clock;

        // ── 2. Assinatura RS256 pelas chaves públicas do Google; as regras do token são conferidas à mão,
        //       para dar a mensagem certa a cada recusa.
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(endpoints.jwks()).toURL())
                .retrying(true).build();
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        this.processor.setJWTClaimsSetVerifier((claims, context) -> { });
    }

    /**
     * Endereços do Google ({@code rayka.google.*-url}).
     *
     * @param auth     tela de login
     * @param token    troca do código
     * @param userinfo perfil
     * @param jwks     chaves públicas do {@code id_token}
     */
    public record Endpoints(String auth, String token, String userinfo, String jwks) {
    }

    /** {@inheritDoc} */
    @Override
    public boolean appLoginAvailable() {
        return !clientId.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public boolean webLoginAvailable() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public URI authorizationUrl(String state) {

        // ── 1. Os parâmetros, na ordem que o Node manda.
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri());
        query.put("response_type", "code");
        query.put("scope", "openid email profile");
        query.put("state", state);
        query.put("prompt", "select_account");

        // ── 2. O endereço da tela de login.
        return URI.create(endpoints.auth() + "?" + form(query));
    }

    /** {@inheritDoc} */
    @Override
    public GoogleProfile profileFromIdToken(String idToken) {

        // ── 1. Assinatura: token que não é do Google nem chega às regras.
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (KeySourceException unreachable) {
            throw new GoogleUnavailableException("JWKS do Google indisponível", unreachable);
        } catch (ParseException | BadJOSEException | JOSEException invalid) {
            throw refused("O Google não reconheceu essa identificação.");
        }

        // ── 2. As mesmas regras, na mesma ordem, de checkTokenInfo do Node.
        if (claims.getAudience() == null || !claims.getAudience().contains(clientId)) {
            throw refused("Identificação do Google emitida para outro aplicativo.");
        }
        if (!ISSUERS.contains(String.valueOf(claims.getIssuer()))) {
            throw refused("Identificação do Google inválida.");
        }
        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.toInstant().isBefore(clock.instant())) {
            throw refused("A identificação do Google expirou. Tente entrar de novo.");
        }
        Object verified = claims.getClaim("email_verified");
        if (!Boolean.TRUE.equals(verified) && !"true".equals(verified)) {
            throw refused("O Google não confirmou o seu e-mail. Use outra conta ou entre com e-mail e senha.");
        }

        // ── 3. Dados da conta.
        Object email = claims.getClaim("email");
        if (claims.getSubject() == null || email == null) {
            throw refused("O Google não devolveu os dados da conta.");
        }
        return profile(claims.getSubject(), email, claims.getClaim("name"), true);
    }

    /** {@inheritDoc} */
    @Override
    public GoogleProfile profileFromCode(String code) {

        // ── 1. Troca o código por um token de acesso.
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri());
        form.put("grant_type", "authorization_code");
        JsonNode token = send(HttpRequest.newBuilder(URI.create(endpoints.token())).timeout(TIMEOUT)
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))).build());
        String accessToken = token.path("access_token").asString("");
        if (accessToken.isEmpty()) {
            throw new GoogleUnavailableException("token: " + token.path("error").asString("sem access_token"), null);
        }

        // ── 2. Lê o perfil.
        JsonNode info = send(HttpRequest.newBuilder(URI.create(endpoints.userinfo())).timeout(TIMEOUT)
                .header("authorization", "Bearer " + accessToken).GET().build());
        if (info.path("sub").asString("").isEmpty() || info.path("email").asString("").isEmpty()) {
            throw new GoogleUnavailableException("userinfo sem sub ou email", null);
        }
        boolean verified = !info.path("email_verified").isBoolean() || info.path("email_verified").asBoolean();
        return profile(info.path("sub").asString(), info.path("email").asString(), info.path("name").asString(""),
                verified);
    }

    /**
     * Envia uma chamada ao Google e lê o JSON; resposta não 2xx ou falha de rede viram indisponibilidade.
     *
     * @param request a chamada
     * @return o corpo
     */
    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GoogleUnavailableException("Google respondeu " + response.statusCode(), null);
            }
            return json.readTree(response.body());
        } catch (IOException failure) {
            throw new GoogleUnavailableException("Google sem resposta", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GoogleUnavailableException("interrompido", interrupted);
        }
    }

    /**
     * Perfil normalizado: e-mail minúsculo, nome até 80.
     *
     * @param sub      id da conta
     * @param email    e-mail
     * @param name     nome (pode ser nulo)
     * @param verified e-mail confirmado
     * @return o perfil
     */
    private static GoogleProfile profile(String sub, Object email, Object name, boolean verified) {
        String text = name == null ? "" : String.valueOf(name);
        return new GoogleProfile(sub, String.valueOf(email).toLowerCase(Locale.ROOT),
                text.length() > 80 ? text.substring(0, 80) : text, verified);
    }

    /**
     * Recusa do token com a mensagem do Node (401).
     *
     * @param message mensagem
     * @return o erro
     */
    private static DomainException refused(String message) {
        return new DomainException(message, 401);
    }

    /**
     * Endereço de volta cadastrado no Google.
     *
     * @return {@code SITE_URL/api/cliente/google/callback}
     */
    private String redirectUri() {
        return siteUrl + "/api/cliente/google/callback";
    }

    /**
     * Corpo {@code application/x-www-form-urlencoded} (e consulta de URL), na ordem dada.
     *
     * @param values campos
     * @return o texto codificado
     */
    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * Codifica um valor de formulário.
     *
     * @param value valor
     * @return codificado
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
