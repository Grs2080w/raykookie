package br.com.kntap.raykookie.customers.application;

import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts;
import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts.Login;
import br.com.kntap.raykookie.customers.application.ports.CustomerSessionStore;
import br.com.kntap.raykookie.customers.application.ports.GoogleAccounts;
import br.com.kntap.raykookie.customers.application.ports.GoogleAccounts.GoogleProfile;
import br.com.kntap.raykookie.customers.domain.CustomerText;
import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Entrada do cliente: cadastro, login com senha, login com Google e saída. Mesmas respostas do Node
 * ({@code backend/app/api/cliente/{cadastro,login,logout,google/token}}), com as correções do plano: limites
 * por IP real guardados no banco (G05) e a mesma mensagem para toda recusa de login (G19).
 */
@Service
public class CustomerSignInUseCases {

    /** Resposta ao limite de tentativas. */
    public static final String TOO_MANY = "Muitas tentativas. Espere alguns minutos e tente de novo.";

    /** Resposta a qualquer login recusado (G19: não revela se a conta existe nem como ela entra). */
    public static final String WRONG_LOGIN =
            "E-mail ou senha incorretos. Se você criou a conta com o Google, use “Continuar com o Google”.";

    /** Contas. */
    private final CustomerAccounts accounts;

    /** Sessões. */
    private final CustomerSessionStore sessions;

    /** Senhas no formato do Node. */
    private final ScryptPasswordHasher hasher;

    /** Limites de tentativa. */
    private final RateLimiter limiter;

    /** Google. */
    private final GoogleAccounts google;

    /** Relógio (vencimento da sessão). */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param accounts contas
     * @param sessions sessões
     * @param hasher   senhas
     * @param limiter  limites
     * @param google   Google
     * @param clock    relógio
     */
    public CustomerSignInUseCases(CustomerAccounts accounts, CustomerSessionStore sessions, ScryptPasswordHasher hasher,
            RateLimiter limiter, GoogleAccounts google, Clock clock) {

        // ── 1. Contas, sessões e senhas.
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;

        // ── 2. Limites, Google e relógio.
        this.limiter = limiter;
        this.google = google;
        this.clock = clock;
    }

    /**
     * Cadastro com e-mail e senha.
     *
     * @param body     corpo ({@code name}, {@code email}, {@code password})
     * @param clientIp IP real
     * @return o token da sessão nova
     */
    public String signUp(Map<String, Object> body, String clientIp) {

        // ── 1. Limite por IP (cada cadastro conta, para frear criação em massa).
        if (!limiter.peek(RateLimits.CUSTOMER_SIGNUP, clientIp)) {
            throw new TooManyRequestsException(TOO_MANY);
        }

        // ── 2. Mesmas validações e mensagens do Node.
        String name = CustomerText.cut(CustomerText.clean(body.get("name")), 80);
        String email = CustomerText.clean(body.get("email")).toLowerCase(Locale.ROOT);
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        if (name.length() < 2) {
            throw new InvalidInputException("Informe seu nome.");
        }
        if (!CustomerText.isEmail(email)) {
            throw new InvalidInputException("Esse e-mail não parece certo. Confira e tente de novo.");
        }
        requirePassword(password);

        // ── 3. Conta a tentativa, cria (e-mail repetido = 409) e já entra.
        limiter.hit(RateLimits.CUSTOMER_SIGNUP, clientIp);
        return openSession(accounts.create(name, email, hasher.hash(password)));
    }

    /**
     * Login com e-mail e senha.
     *
     * @param body     corpo ({@code email}, {@code password})
     * @param clientIp IP real
     * @return o token da sessão nova
     */
    public String login(Map<String, Object> body, String clientIp) {

        // ── 1. Limite por e-mail e IP real.
        String email = CustomerText.clean(body.get("email")).toLowerCase(Locale.ROOT);
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        String subject = email + "|" + clientIp;
        if (!limiter.peek(RateLimits.CUSTOMER_LOGIN, subject)) {
            throw new TooManyRequestsException(TOO_MANY);
        }

        // ── 2. Confere a senha mesmo sem conta (o tempo não revela se o e-mail existe).
        Optional<Login> login = password.length() > 200 ? Optional.empty() : accounts.loginByEmail(email);
        String stored = login.map(Login::passwordHash).orElse(ScryptPasswordHasher.DUMMY_HASH);
        boolean valid = password.length() <= 200 && hasher.verify(password, stored == null ? "" : stored)
                && login.isPresent() && stored != null;

        // ── 3. Recusa sempre com a mesma mensagem; aceita zerando a contagem.
        if (!valid) {
            limiter.hit(RateLimits.CUSTOMER_LOGIN, subject);
            throw new DomainException(WRONG_LOGIN, 401);
        }
        limiter.reset(RateLimits.CUSTOMER_LOGIN, subject);
        return openSession(login.get().id());
    }

    /**
     * Login pelo app com o {@code id_token} do Google.
     *
     * @param idToken token recebido do aparelho
     * @return o token da sessão nova
     */
    public String googleIdToken(Object idToken) {

        // ── 1. Configuração e formato.
        if (!google.appLoginAvailable()) {
            throw new DomainException(
                    "O login com o Google ainda não está disponível. Entre com e-mail e senha.", 503);
        }
        String token = idToken == null ? "" : String.valueOf(idToken);
        if (token.length() < 20 || token.length() > 4000) {
            throw new InvalidInputException("Identificação do Google ausente ou inválida.");
        }

        // ── 2. Confere com as chaves do Google; falha de rede vira 502.
        GoogleProfile profile;
        try {
            profile = google.profileFromIdToken(token);
        } catch (GoogleAccounts.GoogleUnavailableException unavailable) {
            throw new DomainException(
                    "Não foi possível confirmar com o Google agora. Tente de novo em instantes.", 502);
        }
        return openSession(accounts.upsertGoogle(profile.sub(), profile.email(), profile.name()));
    }

    /**
     * Login pelo site, na volta do Google: acha ou cria a conta e abre a sessão.
     *
     * @param profile perfil lido do Google
     * @return o token da sessão nova
     */
    public String googleWeb(GoogleProfile profile) {
        return openSession(accounts.upsertGoogle(profile.sub(), profile.email(), profile.name()));
    }

    /**
     * Saída: apaga a sessão do token, se houver.
     *
     * @param token token da requisição
     */
    public void logout(Optional<String> token) {
        token.map(SessionTokens::hash).ifPresent(sessions::delete);
    }

    /**
     * Abre uma sessão de 30 dias.
     *
     * @param customer cliente
     * @return o token
     */
    private String openSession(CustomerId customer) {
        String token = SessionTokens.newToken();
        sessions.create(customer, SessionTokens.hash(token), clock.instant().plus(SessionCookies.SESSION_TTL));
        return token;
    }

    /**
     * Senha de cadastro: 8 a 200 caracteres.
     *
     * @param password senha
     */
    private static void requirePassword(String password) {
        if (password.length() < 8) {
            throw new InvalidInputException("A senha precisa ter pelo menos 8 caracteres.");
        }
        if (password.length() > 200) {
            throw new InvalidInputException("A senha é longa demais.");
        }
    }
}
