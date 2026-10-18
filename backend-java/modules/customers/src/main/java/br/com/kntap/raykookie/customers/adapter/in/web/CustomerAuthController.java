package br.com.kntap.raykookie.customers.adapter.in.web;

import br.com.kntap.raykookie.customers.application.CustomerSignInUseCases;
import br.com.kntap.raykookie.customers.application.ports.GoogleAccounts;
import br.com.kntap.raykookie.customers.application.ports.GoogleAccounts.GoogleProfile;
import br.com.kntap.raykookie.customers.domain.CustomerText;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import br.com.kntap.raykookie.web.ClientIpResolver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrada do cliente: {@code /api/cliente/cadastro}, {@code /login}, {@code /logout} e o login com Google
 * ({@code /google}, {@code /google/callback}, {@code /google/token}).
 */
@RestController
public class CustomerAuthController {

    /** Cookie do fluxo OAuth do site. */
    static final String OAUTH_COOKIE = "rk_oauth";

    /** Caminho em que o cookie do fluxo OAuth vale. */
    static final String OAUTH_PATH = "/api/cliente/google";

    /** Log das falhas do Google. */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerAuthController.class);

    /** Gerador do {@code state}. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Casos de uso de entrada. */
    private final CustomerSignInUseCases signIn;

    /** Google. */
    private final GoogleAccounts google;

    /** IP real. */
    private final ClientIpResolver clientIp;

    /** Cookies. */
    private final SessionCookies cookies;

    /** {@code SITE_URL} sem a barra final. */
    private final String siteUrl;

    /** Leitor do cookie do fluxo OAuth. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Cria o controller.
     *
     * @param signIn   casos de uso de entrada
     * @param google   Google
     * @param clientIp IP real
     * @param cookies  cookies
     * @param siteUrl  {@code rayka.site-url}
     */
    public CustomerAuthController(CustomerSignInUseCases signIn, GoogleAccounts google, ClientIpResolver clientIp,
            SessionCookies cookies, @Value("${rayka.site-url:https://raykookie.com.br}") String siteUrl) {
        this.signIn = signIn;
        this.google = google;
        this.clientIp = clientIp;
        this.cookies = cookies;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    /**
     * Cadastro.
     *
     * @param body    nome, e-mail e senha
     * @param client  {@code x-client}
     * @param request requisição (IP real)
     * @return 201 com o cookie (e o token para o app)
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/cliente/cadastro")
    public ResponseEntity<Map<String, Object>> signUp(@RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "x-client", required = false) String client, HttpServletRequest request) {
        String token = signIn.signUp(orEmpty(body), clientIp.resolve(request));
        return opened(HttpStatus.CREATED, token, client);
    }

    /**
     * Login com senha.
     *
     * @param body    e-mail e senha
     * @param client  {@code x-client}
     * @param request requisição (IP real)
     * @return 200 com o cookie (e o token para o app)
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/cliente/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "x-client", required = false) String client, HttpServletRequest request) {
        return opened(HttpStatus.OK, signIn.login(orEmpty(body), clientIp.resolve(request)), client);
    }

    /**
     * Saída.
     *
     * @param request requisição (token)
     * @return {@code {"ok":true}} e o cookie apagado
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/cliente/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        signIn.logout(SessionTokens.fromRequest(request, SessionTokens.CUSTOMER_COOKIE));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear(SessionTokens.CUSTOMER_COOKIE).toString())
                .body(Map.of("ok", true));
    }

    /**
     * Login com Google pelo app ({@code idToken} obtido no aparelho).
     *
     * @param body   {@code idToken}
     * @param client {@code x-client}
     * @return 200 com o cookie (e o token para o app)
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/cliente/google/token")
    public ResponseEntity<Map<String, Object>> googleToken(@RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "x-client", required = false) String client) {
        return opened(HttpStatus.OK, signIn.googleIdToken(orEmpty(body).get("idToken")), client);
    }

    /**
     * Passo 1 do login com Google pelo site: manda para a tela do Google, guardando {@code state} e destino.
     *
     * @param next destino depois do login
     * @return redirecionamento
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/cliente/google")
    public ResponseEntity<Void> googleStart(@RequestParam(value = "next", required = false) String next) {

        // ── 1. Sem configuração, volta para a tela de entrar com o aviso.
        if (!google.webLoginAvailable()) {
            return redirect("/cliente/entrar?erro=google-indisponivel", false);
        }

        // ── 2. state aleatório de 24 bytes, guardado num cookie que só vale no fluxo, por 10 minutos.
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Map<String, String> saved = new LinkedHashMap<>();
        saved.put("state", state);
        saved.put("next", CustomerText.safeNext(next));
        String value = URLEncoder.encode(json.writeValueAsString(saved), StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(google.authorizationUrl(state))
                .header(HttpHeaders.SET_COOKIE, cookies.scoped(OAUTH_COOKIE, value, OAUTH_PATH,
                        Duration.ofMinutes(10)).toString())
                .build();
    }

    /**
     * Passo 2: o Google volta com {@code code} e {@code state}.
     *
     * @param code    código
     * @param state   state devolvido
     * @param error   erro devolvido (ex.: a pessoa cancelou)
     * @param request requisição (cookie do fluxo)
     * @return redirecionamento para o destino ou para a tela de entrar com o erro
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/cliente/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error, HttpServletRequest request) {

        // ── 1. Cancelado, sem código, ou state diferente do guardado: recusa.
        if (error != null) {
            return redirect("/cliente/entrar?erro=google-cancelado", true);
        }
        JsonNode saved = savedFlow(request);
        String expected = saved.path("state").asString("");
        if (code == null || expected.isEmpty() || !expected.equals(state)) {
            return redirect("/cliente/entrar?erro=google-invalido", true);
        }
        if (!google.webLoginAvailable()) {
            return redirect("/cliente/entrar?erro=google-indisponivel", true);
        }

        // ── 2. Troca o código, exige e-mail confirmado e abre a sessão.
        try {
            GoogleProfile profile = google.profileFromCode(code);
            if (!profile.emailVerified()) {
                return redirect("/cliente/entrar?erro=google-email", true);
            }
            String token = signIn.googleWeb(profile);
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create(siteUrl + CustomerText.safeNext(saved.path("next").asString(""))))
                    .header(HttpHeaders.SET_COOKIE, cookies.scoped(OAUTH_COOKIE, "", OAUTH_PATH, Duration.ZERO)
                            .toString())
                    .header(HttpHeaders.SET_COOKIE, cookies.open(SessionTokens.CUSTOMER_COOKIE, token).toString())
                    .build();
        } catch (GoogleAccounts.GoogleUnavailableException failure) {
            LOG.warn("Login com Google pelo site falhou: {}", failure.getMessage());
            return redirect("/cliente/entrar?erro=google-falhou", true);
        }
    }

    /**
     * O que foi guardado no cookie do fluxo OAuth.
     *
     * @param request requisição
     * @return o JSON guardado, ou vazio
     */
    private JsonNode savedFlow(HttpServletRequest request) {
        Cookie[] all = request.getCookies() == null ? new Cookie[0] : request.getCookies();
        String raw = Arrays.stream(all).filter(cookie -> OAUTH_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse("");
        try {
            return json.readTree(raw.isEmpty() ? "{}" : URLDecoder.decode(raw, StandardCharsets.UTF_8));
        } catch (JacksonException | IllegalArgumentException invalid) {
            return json.createObjectNode();
        }
    }

    /**
     * Redireciona para uma página do site.
     *
     * @param path         caminho no site
     * @param clearOauth   apaga o cookie do fluxo OAuth
     * @return o redirecionamento
     */
    private ResponseEntity<Void> redirect(String path, boolean clearOauth) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(siteUrl + path));
        if (clearOauth) {
            response.header(HttpHeaders.SET_COOKIE,
                    cookies.scoped(OAUTH_COOKIE, "", OAUTH_PATH, Duration.ZERO).toString());
        }
        return response.build();
    }

    /**
     * Sessão aberta: cookie sempre; token no corpo só para o app.
     *
     * @param status status
     * @param token  token
     * @param client {@code x-client}
     * @return a resposta
     */
    private ResponseEntity<Map<String, Object>> opened(HttpStatus status, String token, String client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        if ("mobile".equals(client)) {
            body.put("token", token);
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.open(SessionTokens.CUSTOMER_COOKIE, token).toString())
                .body(body);
    }

    /**
     * Corpo ausente vale como objeto vazio, como o {@code req.json().catch(() => ({}))} do Node.
     *
     * @param body corpo
     * @return o corpo ou um mapa vazio
     */
    static Map<String, Object> orEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }
}
