package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Alertas de margem e preço (tabela {@code price_alerts}).
 */
public interface PriceAlerts {

    /**
     * Os 50 alertas não lidos mais novos.
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> unread(OwnerId owner);

    /**
     * Grava um alerta.
     *
     * @param owner        dona
     * @param recipeId     receita
     * @param ingredientId ingrediente que mudou
     * @param alert        o alerta
     * @param cause        texto da causa
     */
    void create(OwnerId owner, long recipeId, long ingredientId, PricingEngine.MarginAlert alert, String cause);

    /**
     * Receita e preço sugerido de um alerta.
     *
     * @param owner dona
     * @param id    alerta
     * @return receita e sugerido, ou vazio se não for da dona
     */
    Optional<Suggestion> suggestion(OwnerId owner, long id);

    /**
     * Marca o alerta como lido.
     *
     * @param owner dona
     * @param id    alerta
     */
    void markRead(OwnerId owner, long id);

    /**
     * O que aplicar de um alerta.
     *
     * @param recipeId  receita
     * @param suggested preço sugerido (nulo = nenhum)
     */
    record Suggestion(long recipeId, Double suggested) {
    }
}
