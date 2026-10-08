package br.com.kntap.raykookie.customers.application;

import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts;
import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts.Link;
import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts.Login;
import br.com.kntap.raykookie.customers.domain.PrivacyType;
import br.com.kntap.raykookie.customers.domain.Profile;
import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.security.UnauthorizedException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Direitos do titular (LGPD) na conta do cliente: ver, corrigir, exportar, desligar o Google e excluir
 * ({@code backend/lib/customer-privacy.ts}). Excluir = anonimizar: pedidos e valores ficam (obrigação fiscal).
 */
@Service
public class CustomerAccountUseCases {

    /** Contas. */
    private final CustomerAccounts accounts;

    /** Senhas. */
    private final ScryptPasswordHasher hasher;

    /** A dona da loja (registro da exclusão). */
    private final ShopOwner shopOwner;

    /** Relógio (data da exportação). */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param accounts  contas
     * @param hasher    senhas
     * @param shopOwner a dona da loja
     * @param clock     relógio
     */
    public CustomerAccountUseCases(CustomerAccounts accounts, ScryptPasswordHasher hasher, ShopOwner shopOwner,
            Clock clock) {
        this.accounts = accounts;
        this.hasher = hasher;
        this.shopOwner = shopOwner;
        this.clock = clock;
    }

    /**
     * O perfil do cliente ({@code GET /api/cliente/me}).
     *
     * @param customer cliente
     * @return os campos do Node
     */
    public Map<String, Object> profile(CustomerId customer) {
        return accounts.profile(customer).orElseThrow(() -> new UnauthorizedException("Entre para continuar."));
    }

    /**
     * Correção dos dados.
     *
     * @param customer cliente
     * @param body     corpo do {@code PATCH}
     */
    public void updateProfile(CustomerId customer, Map<String, Object> body) {
        accounts.updateProfile(customer, Profile.from(body));
    }

    /**
     * "Meus dados": a conta, as solicitações e os tipos que dá para pedir.
     *
     * @param customer cliente
     * @return o JSON do Node
     */
    public Map<String, Object> myData(CustomerId customer) {
        Map<String, Object> data = new LinkedHashMap<>(accounts.myData(customer)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada.")));
        data.put("requests", accounts.requestsOf(customer));
        data.put("request_types", PrivacyType.REQUESTABLE);
        data.put("deadline_days", PrivacyType.DEADLINE_DAYS);
        return data;
    }

    /**
     * Cópia dos dados (portabilidade).
     *
     * @param customer cliente
     * @return o JSON da exportação
     */
    public Map<String, Object> export(CustomerId customer) {

        // ── 1. Quando e a conta.
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exported_at", NodeRows.timestamp(clock.instant()));
        export.put("account", accounts.myData(customer).orElse(null));

        // ── 2. Tudo o que está ligado à pessoa.
        export.put("orders", accounts.exportOrders(customer));
        export.put("privacy_requests", accounts.exportRequests(customer));
        export.put("notification_devices", accounts.exportDevices(customer));
        return export;
    }

    /**
     * Tira o vínculo com o Google, se a conta tiver senha para continuar entrando.
     *
     * @param customer cliente
     */
    public void unlinkGoogle(CustomerId customer) {
        Link link = accounts.link(customer).orElse(new Link(false, false));
        if (!link.hasGoogle()) {
            throw new ConflictException("Sua conta não está ligada ao Google.");
        }
        if (!link.hasPassword()) {
            throw new ConflictException("Sua conta só entra pelo Google. Para desvincular, exclua a conta ou "
                    + "peça uma solicitação de dados.");
        }
        accounts.unlinkGoogle(customer);
    }

    /**
     * Exclui a conta (anonimiza), conferindo a confirmação, a senha e os pedidos em andamento.
     *
     * @param customer cliente
     * @param confirm  texto digitado (precisa ser EXCLUIR)
     * @param password senha (contas com senha)
     */
    public void delete(CustomerId customer, Object confirm, Object password) {

        // ── 1. Confirmação explícita.
        if (!"EXCLUIR".equals(String.valueOf(confirm == null ? "" : confirm).strip().toUpperCase(Locale.ROOT))) {
            throw new InvalidInputException("Digite EXCLUIR para confirmar.");
        }

        // ── 2. A conta existe e, se tem senha, ela confere.
        Login account = accounts.activeAccount(customer)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada."));
        String typed = password == null ? "" : String.valueOf(password);
        if (account.passwordHash() != null && !hasher.verify(typed, account.passwordHash())) {
            throw new DomainException("Senha incorreta.", 403);
        }

        // ── 3. Sem pedidos em andamento.
        int open = accounts.openOrders(customer);
        if (open > 0) {
            String plural = open > 1 ? "s" : "";
            throw new ConflictException("Você tem " + open + " pedido" + plural + " em andamento. Quando forem "
                    + "entregues (ou se você cancelá-los), é só voltar aqui e excluir a conta.");
        }

        // ── 4. Anonimiza e registra para a dona.
        Optional<OwnerId> owner = shopOwner.isPending() ? Optional.empty() : Optional.of(shopOwner.id());
        accounts.anonymize(customer, owner);
    }
}
