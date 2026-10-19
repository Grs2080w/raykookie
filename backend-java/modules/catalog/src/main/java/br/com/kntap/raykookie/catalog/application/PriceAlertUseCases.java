package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.catalog.application.ports.PriceAlerts;
import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertas de margem e preço: listar, dispensar e aplicar o preço sugerido.
 */
@Service
public class PriceAlertUseCases {

    /** Alertas. */
    private final PriceAlerts alerts;

    /** Receitas. */
    private final Recipes recipes;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria os casos de uso.
     *
     * @param alerts  alertas
     * @param recipes receitas
     * @param audit   histórico de auditoria
     */
    public PriceAlertUseCases(PriceAlerts alerts, Recipes recipes, AuditTrail audit) {
        this.alerts = alerts;
        this.recipes = recipes;
        this.audit = audit;
    }

    /**
     * Alertas ainda não lidos, mais novos primeiro.
     *
     * @param owner dona
     * @return os alertas
     */
    public List<Map<String, Object>> unread(OwnerId owner) {
        return alerts.unread(owner);
    }

    /**
     * Marca como lido (some do painel).
     *
     * @param owner dona
     * @param id    alerta
     */
    public void dismiss(OwnerId owner, long id) {
        alerts.markRead(owner, id);
    }

    /**
     * Fixa o preço sugerido no cardápio da receita e marca o alerta como lido.
     *
     * @param owner dona
     * @param id    alerta
     * @return {@code {"ok":true,"sale_price":…}}
     * @throws NotFoundException se o alerta não for da dona ou não tiver preço sugerido
     */
    @Transactional
    public Map<String, Object> apply(OwnerId owner, long id) {
        PriceAlerts.Suggestion suggestion = alerts.suggestion(owner, id)
                .filter(found -> found.suggested() != null && found.suggested() > 0)
                .orElseThrow(() -> new NotFoundException("Alerta não encontrado."));
        Map<String, Object> was = recipes.list(owner).stream()
                .filter(r -> r.get("id") instanceof Number n && n.longValue() == suggestion.recipeId()).findFirst()
                .orElse(Map.of());
        recipes.setSalePrice(owner, suggestion.recipeId(), suggestion.suggested());
        alerts.markRead(owner, id);
        audit.record(owner, Entry.of("preco", "aplicou", "recipe", suggestion.recipeId(),
                AuditValues.pick(was, List.of("sale_price")), Map.of("sale_price", suggestion.suggested()),
                "Preço sugerido aplicado em " + was.getOrDefault("name", "#" + suggestion.recipeId()) + ": "
                        + AuditValues.brl(was.get("sale_price")) + " para " + AuditValues.brl(suggestion.suggested())));
        return Map.of("ok", true, "sale_price", NodeRows.number(suggestion.suggested()));
    }
}
