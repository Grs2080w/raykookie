package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.PriceAlertUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alertas de margem e preço: {@code /api/price-alerts}.
 */
@RestController
public class PriceAlertsController {

    /** Casos de uso. */
    private final PriceAlertUseCases alerts;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param alerts casos de uso
     * @param actor  quem está chamando
     */
    public PriceAlertsController(PriceAlertUseCases alerts, CurrentActor actor) {
        this.alerts = alerts;
        this.actor = actor;
    }

    /**
     * Alertas não lidos, mais novos primeiro.
     *
     * @return os alertas
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/price-alerts")
    public List<Map<String, Object>> unread() {
        return alerts.unread(actor.requireOwner());
    }

    /**
     * Marca o alerta como lido.
     *
     * @param id alerta
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/price-alerts/{id}")
    public Map<String, Object> dismiss(@PathVariable long id) {
        alerts.dismiss(actor.requireOwner(), id);
        return Map.of("ok", true);
    }

    /**
     * Fixa o preço sugerido do alerta na receita.
     *
     * @param id alerta
     * @return {@code {"ok":true,"sale_price":…}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/price-alerts/{id}/apply")
    public Map<String, Object> apply(@PathVariable long id) {
        return alerts.apply(actor.requireOwner(), id);
    }
}
