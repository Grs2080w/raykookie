package br.com.kntap.raykookie.identity.adapter.in.web;

import br.com.kntap.raykookie.identity.application.CurrentOwnerQuery;
import br.com.kntap.raykookie.identity.application.LoginUseCase;
import br.com.kntap.raykookie.identity.application.LogoutUseCase;
import br.com.kntap.raykookie.identity.application.RegisterOwnerUseCase;
import br.com.kntap.raykookie.identity.domain.OwnerAccount;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import br.com.kntap.raykookie.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas da conta da dona: {@code /api/auth/login}, {@code /api/auth/logout}, {@code /api/auth/register} e
 * {@code /api/me}, com as respostas do Node.
 */
@RestController
public class AuthController {

    /** Cabeçalho com que o app pede o token no corpo. */
    static final String CLIENT_HEADER = "x-client";

    /** Login. */
    private final LoginUseCase login;

    /** Logout. */
    private final LogoutUseCase logout;

    /** Cadastro. */
    private final RegisterOwnerUseCase register;

    /** Conta logada. */
    private final CurrentOwnerQuery currentOwner;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** IP real do cliente. */
    private final ClientIpResolver clientIp;

    /** Cookies de sessão. */
    private final SessionCookies cookies;

    /**
     * Cria o controller.
     *
     * @param login        login
     * @param logout       logout
     * @param register     cadastro
     * @param currentOwner conta logada
     * @param actor        quem está chamando
     * @param clientIp     IP real do cliente
     * @param cookies      cookies de sessão
     */
    public AuthController(LoginUseCase login, LogoutUseCase logout, RegisterOwnerUseCase register,
            CurrentOwnerQuery currentOwner, CurrentActor actor, ClientIpResolver clientIp, SessionCookies cookies) {

        // ── 1. Casos de uso da conta.
        this.login = login;
        this.logout = logout;
        this.register = register;
        this.currentOwner = currentOwner;

        // ── 2. Peças da borda HTTP: quem chama, IP real e cookies.
        this.actor = actor;
        this.clientIp = clientIp;
        this.cookies = cookies;
    }

    /**
     * Entra no painel.
     *
     * @param body    usuário e senha
     * @param client  {@code x-client} ({@code mobile} = token no corpo)
     * @param request requisição (IP real)
     * @return {@code {"ok":true}} (+ {@code token} para o app) e o cookie da sessão
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) CredentialsRequest body,
            @RequestHeader(value = CLIENT_HEADER, required = false) String client, HttpServletRequest request) {
        CredentialsRequest credentials = body == null ? new CredentialsRequest(null, null) : body;
        String token = login.login(credentials.username(), credentials.password(), clientIp.resolve(request));
        return opened(token, client);
    }

    /**
     * Cria a conta da dona (só na instalação nova; fora dela, 404).
     *
     * @param body   usuário e senha
     * @param client {@code x-client}
     * @return como o login
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) CredentialsRequest body,
            @RequestHeader(value = CLIENT_HEADER, required = false) String client) {
        CredentialsRequest credentials = body == null ? new CredentialsRequest(null, null) : body;
        return opened(register.register(credentials.username(), credentials.password()), client);
    }

    /**
     * Sai: apaga a sessão e o cookie.
     *
     * @param request requisição (token por Bearer ou cookie)
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        logout.logout(SessionTokens.fromRequest(request, SessionTokens.OWNER_COOKIE));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear(SessionTokens.OWNER_COOKIE).toString())
                .body(Map.of("ok", true));
    }

    /**
     * A conta logada.
     *
     * @return id e usuário
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/me")
    public OwnerView me() {
        OwnerAccount account = currentOwner.byId(actor.requireOwner());
        return new OwnerView(account.id().value(), account.username());
    }

    /**
     * Resposta de sessão aberta: cookie sempre; token no corpo só para o app.
     *
     * @param token  token da sessão
     * @param client {@code x-client}
     * @return a resposta
     */
    private ResponseEntity<Map<String, Object>> opened(String token, String client) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        if ("mobile".equals(client)) {
            body.put("token", token);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.open(SessionTokens.OWNER_COOKIE, token).toString())
                .body(body);
    }
}
