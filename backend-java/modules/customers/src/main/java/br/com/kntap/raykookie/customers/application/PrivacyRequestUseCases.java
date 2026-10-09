package br.com.kntap.raykookie.customers.application;

import br.com.kntap.raykookie.customers.api.events.PrivacyRequestCreated;
import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts;
import br.com.kntap.raykookie.customers.domain.CustomerText;
import br.com.kntap.raykookie.customers.domain.PrivacyType;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.ShopOwner;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Solicitações sobre os dados: o cliente pede, a dona responde em até 15 dias.
 */
@Service
public class PrivacyRequestUseCases {

    /** Contas e solicitações. */
    private final CustomerAccounts accounts;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /** Eventos (o aviso para a dona). */
    private final ApplicationEventPublisher events;

    /**
     * Cria os casos de uso.
     *
     * @param accounts  contas e solicitações
     * @param shopOwner a dona da loja
     * @param events    eventos
     */
    public PrivacyRequestUseCases(CustomerAccounts accounts, ShopOwner shopOwner, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.shopOwner = shopOwner;
        this.events = events;
    }

    /**
     * O cliente faz uma solicitação.
     *
     * @param customer cliente
     * @param type     tipo recebido
     * @param details  detalhes recebidos
     * @return o id da solicitação
     */
    @Transactional
    public long create(CustomerId customer, Object type, Object details) {

        // ── 1. Tipo da lista e detalhes suficientes para "outro".
        if (!PrivacyType.isRequestable(type)) {
            throw new InvalidInputException("Escolha o tipo de solicitação.");
        }
        String text = CustomerText.collapse(details, 1000);
        if ("outro".equals(type) && text.length() < 10) {
            throw new InvalidInputException("Conte um pouco mais sobre o que você precisa.");
        }

        // ── 2. Vai para a dona (que precisa existir) e ela é avisada depois do commit.
        if (shopOwner.isPending()) {
            throw new InvalidInputException("Serviço indisponível no momento.");
        }
        OwnerId owner = shopOwner.id();
        long id = accounts.createRequest(owner, customer, String.valueOf(type), text);
        events.publishEvent(new PrivacyRequestCreated(owner, id, String.valueOf(type)));
        return id;
    }

    /**
     * Solicitações recebidas pela dona.
     *
     * @param owner a dona
     * @return as solicitações com o prazo
     */
    public List<Map<String, Object>> forOwner(OwnerId owner) {
        return accounts.requestsFor(owner, PrivacyType.DEADLINE_DAYS);
    }

    /**
     * A dona conclui uma solicitação.
     *
     * @param id     id recebido na rota
     * @param owner  a dona
     * @param answer resposta
     */
    public void answer(String id, OwnerId owner, Object answer) {
        String text = CustomerText.cut(CustomerText.clean(answer), 1000);
        if (text.length() < 3) {
            throw new InvalidInputException("Escreva a resposta ao cliente.");
        }
        if (!id.matches("\\d{1,9}") || !accounts.answer(Long.parseLong(id), owner, text)) {
            throw new NotFoundException("Solicitação não encontrada ou já concluída.");
        }
    }
}
