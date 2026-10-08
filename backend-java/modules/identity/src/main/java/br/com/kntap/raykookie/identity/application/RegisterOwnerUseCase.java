package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import br.com.kntap.raykookie.identity.api.events.OwnerRegistered;
import br.com.kntap.raykookie.identity.application.ports.OwnerAccounts;
import br.com.kntap.raykookie.identity.application.ports.OwnerSessionStore;
import br.com.kntap.raykookie.identity.domain.Credentials;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.SecurityProperties;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.web.ErrorMessages;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de dona (G01). No Node, qualquer pessoa criava uma conta e entrava no painel; aqui a rota só
 * existe numa instalação nova ({@code REGISTRATION_ENABLED=true} e nenhuma conta) e só cadastra o
 * usuário do {@code SHOP_OWNER}. Fora disso responde 404, como uma rota que não existe.
 */
@Service
public class RegisterOwnerUseCase {

    /** Contas. */
    private final OwnerAccounts accounts;

    /** Sessões. */
    private final OwnerSessionStore sessions;

    /** Senhas. */
    private final ScryptPasswordHasher hasher;

    /** A dona da loja (pendente na instalação nova). */
    private final ShopOwner shopOwner;

    /** {@code REGISTRATION_ENABLED}. */
    private final boolean registrationOpen;

    /** Eventos (o catálogo cria as categorias padrão). */
    private final ApplicationEventPublisher events;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria o caso de uso.
     *
     * @param accounts   contas
     * @param sessions   sessões
     * @param hasher     senhas
     * @param shopOwner  a dona da loja
     * @param properties configuração ({@code REGISTRATION_ENABLED})
     * @param events     eventos
     * @param clock      relógio
     */
    public RegisterOwnerUseCase(OwnerAccounts accounts, OwnerSessionStore sessions, ScryptPasswordHasher hasher,
            ShopOwner shopOwner, SecurityProperties properties, ApplicationEventPublisher events, Clock clock) {

        // ── 1. Contas, sessões e senhas.
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;

        // ── 2. Regras da instalação nova e o que sai daqui (evento, vencimento da sessão).
        this.shopOwner = shopOwner;
        this.registrationOpen = properties.registrationOpen();
        this.events = events;
        this.clock = clock;
    }

    /**
     * Cadastra a dona e abre a sessão dela.
     *
     * @param username usuário
     * @param password senha
     * @return o token da sessão
     * @throws NotFoundException     com o cadastro fechado ou já havendo conta
     * @throws InvalidInputException com usuário ou senha fora da regra, ou usuário que não é o SHOP_OWNER
     */
    @Transactional
    public String register(String username, String password) {

        // ── 1. Fora da instalação nova, a rota "não existe".
        if (!registrationOpen || accounts.any()) {
            throw new NotFoundException(ErrorMessages.NOT_FOUND);
        }

        // ── 2. Mesmas regras e mensagens do Node; e só o usuário configurado como dona.
        String name = Credentials.requireValidUsername(username == null ? "" : username.strip());
        Credentials.requireValidNewPassword(password);
        if (!name.equalsIgnoreCase(shopOwner.username())) {
            throw new InvalidInputException("Cadastre o usuário definido em SHOP_OWNER.");
        }

        // ── 3. Cria a conta (nome repetido vira 409 no adaptador); depois do commit ela vira a dona da loja.
        OwnerId id = accounts.create(name, hasher.hash(password));
        events.publishEvent(new OwnerRegistered(id, name));

        // ── 4. Já entra, como no Node.
        String token = SessionTokens.newToken();
        sessions.create(id, SessionTokens.hash(token), clock.instant().plus(SessionCookies.SESSION_TTL));
        return token;
    }
}
