package br.com.kntap.raykookie.customers.adapter.in.web;

import br.com.kntap.raykookie.customers.application.PrivacyRequestUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solicitações de privacidade, do lado da dona: {@code /api/privacy-requests}.
 */
@RestController
public class PrivacyRequestsController {

    /** Solicitações. */
    private final PrivacyRequestUseCases requests;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param requests solicitações
     * @param actor    quem está chamando
     */
    public PrivacyRequestsController(PrivacyRequestUseCases requests, CurrentActor actor) {
        this.requests = requests;
        this.actor = actor;
    }

    /**
     * As solicitações recebidas, com o prazo legal.
     *
     * @return até 100, as abertas primeiro
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/privacy-requests")
    public List<Map<String, Object>> list() {
        return requests.forOwner(actor.requireOwner());
    }

    /**
     * Conclui uma solicitação com a resposta ao cliente.
     *
     * @param id   id da solicitação
     * @param body {@code answer}
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/privacy-requests/{id}")
    public Map<String, Object> answer(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        requests.answer(id, actor.requireOwner(), CustomerAuthController.orEmpty(body).get("answer"));
        return Map.of("ok", true);
    }
}
