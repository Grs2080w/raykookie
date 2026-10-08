package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import br.com.kntap.raykookie.identity.application.ports.OwnerAccounts;
import br.com.kntap.raykookie.identity.application.ports.OwnerSessionStore;
import br.com.kntap.raykookie.identity.domain.Credentials;
import br.com.kntap.raykookie.identity.domain.OwnerAccount;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.security.UnauthorizedException;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Login da dona. Mesmas respostas do Node, com três correções: o limite de tentativas é por usuário e IP
 * real, guardado no banco (G05); só a dona da loja entra (G24); senha com mais de 200 caracteres é
 * recusada antes do scrypt.
 */
@Service
public class LoginUseCase {

    /** Resposta a qualquer usuário ou senha errados (não revela qual dos dois). */
    public static final String WRONG_CREDENTIALS = "Usuário ou senha incorretos.";

    /** Resposta com o limite de tentativas esgotado. */
    public static final String TOO_MANY_ATTEMPTS = "Muitas tentativas. Espere alguns minutos e tente de novo.";

    /** Contas. */
    private final OwnerAccounts accounts;

    /** Sessões. */
    private final OwnerSessionStore sessions;

    /** Senhas. */
    private final ScryptPasswordHasher hasher;

    /** Limites de tentativa. */
    private final RateLimiter limiter;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /** Relógio (vencimento da sessão). */
    private final Clock clock;

    /**
     * Cria o caso de uso.
     *
     * @param accounts  contas
     * @param sessions  sessões
     * @param hasher    senhas
     * @param limiter   limites de tentativa
     * @param shopOwner a dona da loja
     * @param clock     relógio
     */
    public LoginUseCase(OwnerAccounts accounts, OwnerSessionStore sessions, ScryptPasswordHasher hasher,
            RateLimiter limiter, ShopOwner shopOwner, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.limiter = limiter;
        this.shopOwner = shopOwner;
        this.clock = clock;
    }

    /**
     * Confere usuário e senha e abre uma sessão.
     *
     * @param username usuário digitado
     * @param password senha digitada
     * @param clientIp IP real do cliente ({@code ClientIpResolver})
     * @return o token da sessão nova
     * @throws TooManyRequestsException com 8 erros na janela de 10 minutos
     * @throws UnauthorizedException    com usuário ou senha errados, ou conta que não é a dona
     */
    public String login(String username, String password, String clientIp) {

        // ── 1. Senha gigante nem chega ao scrypt; limite esgotado nem confere a senha.
        String name = username == null ? "" : username.strip();
        String pass = password == null ? "" : password;
        Credentials.requireNotTooLong(pass);
        String subject = name.toLowerCase(Locale.ROOT) + "|" + clientIp;
        if (!limiter.peek(RateLimits.OWNER_LOGIN, subject)) {
            throw new TooManyRequestsException(TOO_MANY_ATTEMPTS);
        }

        // ── 2. Confere a senha mesmo sem conta (o tempo não revela se o usuário existe) e exige a dona.
        Optional<OwnerAccount> account = accounts.findByUsername(name);
        String stored = account.map(OwnerAccount::passwordHash).orElse(ScryptPasswordHasher.DUMMY_HASH);
        boolean valid = hasher.verify(pass, stored) && account.isPresent() && shopOwner.is(account.get().id());

        // ── 3. Erro: conta a tentativa e responde sempre a mesma mensagem.
        if (!valid) {
            limiter.hit(RateLimits.OWNER_LOGIN, subject);
            throw new UnauthorizedException(WRONG_CREDENTIALS);
        }

        // ── 4. Certo: zera a contagem e abre a sessão de 30 dias.
        limiter.reset(RateLimits.OWNER_LOGIN, subject);
        String token = SessionTokens.newToken();
        sessions.create(account.get().id(), SessionTokens.hash(token),
                clock.instant().plus(SessionCookies.SESSION_TTL));
        return token;
    }
}
