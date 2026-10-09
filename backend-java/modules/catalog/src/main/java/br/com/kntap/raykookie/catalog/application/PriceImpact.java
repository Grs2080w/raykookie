package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.PriceAlerts;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Quando o preço de um ingrediente muda: guarda no histórico e avisa se alguma receita perdeu margem ou vai
 * subir de preço (porte de {@code backend/lib/price-impact.ts}).
 *
 * <p>Sem transação própria, como no Node: quem chama já gravou a mudança, e uma falha aqui (é um extra) só é
 * registrada, nunca desfaz a edição.
 */
@Service
public class PriceImpact {

    /** Log das falhas. */
    private static final Logger LOG = LoggerFactory.getLogger(PriceImpact.class);

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Alertas. */
    private final PriceAlerts alerts;

    /** Cálculo das receitas. */
    private final RecipePricing pricing;

    /** Avisos para a dona. */
    private final ApplicationEventPublisher events;

    /**
     * Cria o serviço.
     *
     * @param ingredients ingredientes
     * @param alerts      alertas
     * @param pricing     cálculo das receitas
     * @param events      avisos
     */
    public PriceImpact(Ingredients ingredients, PriceAlerts alerts, RecipePricing pricing,
            ApplicationEventPublisher events) {
        this.ingredients = ingredients;
        this.alerts = alerts;
        this.pricing = pricing;
        this.events = events;
    }

    /**
     * Registra a mudança e cria os alertas ({@code onPriceChange}). Nunca lança erro.
     *
     * @param change a mudança
     * @return quantos alertas foram criados
     */
    public int priceChanged(PriceChange change) {
        try {
            return record(change);
        } catch (RuntimeException error) {
            LOG.error("Falha ao registrar a mudança de preço do ingrediente {}", change.ingredientId(), error);
            return 0;
        }
    }

    /**
     * Grava o histórico e compara cada receita antes e depois.
     *
     * @param change a mudança
     * @return quantos alertas foram criados
     */
    private int record(PriceChange change) {

        // ── 1. Nada mudou, ou o ingrediente não é da dona: nada a fazer.
        if (change.before().equals(change.after())) {
            return 0;
        }
        Optional<Ingredients.NameAndUnit> ingredient = ingredients.nameAndUnit(change.owner(), change.ingredientId());
        if (ingredient.isEmpty()) {
            return 0;
        }

        // ── 2. Histórico de preços.
        ingredients.markPriceUpdated(change.ingredientId());
        if (change.after().size() > 0) {
            ingredients.recordQuote(change.owner(), change.ingredientId(), change.supplierId(), change.after(),
                    ingredient.get().unit(), change.source());
        }

        // ── 3. Alertas das receitas que usam o ingrediente.
        return alertRecipes(change, ingredient.get());
    }

    /**
     * Compara cada receita que usa o ingrediente com o custo antigo e o novo.
     *
     * @param change     a mudança
     * @param ingredient nome e unidade do ingrediente
     * @return quantos alertas foram criados
     */
    private int alertRecipes(PriceChange change, Ingredients.NameAndUnit ingredient) {

        // ── 1. Custos de agora e de antes (só este ingrediente muda).
        RecipePricing.Context context = pricing.context(change.owner());
        Map<Long, Double> before = new HashMap<>(context.costs());
        before.put(change.ingredientId(), unitCost(change.before()));

        // ── 2. Uma comparação por receita que usa o ingrediente.
        int created = 0;
        for (Map<String, Object> row : context.rows()) {
            if (RecipePricing.ingredientIds(row).contains(change.ingredientId())
                    && alertRecipe(change, ingredient, row, before, context)) {
                created++;
            }
        }
        return created;
    }

    /**
     * Compara uma receita e grava o alerta, se houver.
     *
     * @param change     a mudança
     * @param ingredient nome e unidade
     * @param row        a receita
     * @param before     custos de antes
     * @param context    custos de agora, ajustes e gastos
     * @return verdadeiro se criou um alerta
     */
    private boolean alertRecipe(PriceChange change, Ingredients.NameAndUnit ingredient, Map<String, Object> row,
            Map<Long, Double> before, RecipePricing.Context context) {

        // ── 1. Antes e depois, com e sem o preço fixado.
        PricingEngine.Result b0 = PricingEngine.calculate(RecipePricing.input(row, before, context));
        PricingEngine.Result a0 = PricingEngine.calculate(RecipePricing.input(row, context.costs(), context));
        Double sale = RecipePricing.salePrice(row);
        Optional<PricingEngine.MarginAlert> alert = PricingEngine.marginAlert(PricingEngine.withSalePrice(b0, sale),
                PricingEngine.withSalePrice(a0, sale), sale != null && sale > 0, context.settings().minMarginPct(),
                a0.rounded());
        if (alert.isEmpty()) {
            return false;
        }

        // ── 2. Grava e avisa a dona.
        long recipeId = ((Number) row.get("id")).longValue();
        String cause = cause(ingredient, change.before(), change.after());
        alerts.create(change.owner(), recipeId, change.ingredientId(), alert.get(), cause);
        events.publishEvent(new OwnerNotice.PriceAlert(change.owner(), recipeId, (String) row.get("name"),
                alert.get().kind(), alert.get().before(), alert.get().after(), cause));
        return true;
    }

    /**
     * Texto da causa: "Chocolate: R$ 32,00 → R$ 40,00/kg".
     *
     * @param ingredient nome e unidade
     * @param before     preço antes
     * @param after      preço depois
     * @return o texto
     */
    static String cause(Ingredients.NameAndUnit ingredient, PriceState before, PriceState after) {
        double scale = "un".equals(ingredient.unit()) ? 1 : 1000;
        String per = switch (ingredient.unit()) {
            case "g" -> "kg";
            case "ml" -> "L";
            default -> "un";
        };
        return ingredient.name() + ": R$ " + PricingEngine.fixed2(unitCost(before) * scale) + " → R$ "
                + PricingEngine.fixed2(unitCost(after) * scale) + "/" + per;
    }

    /**
     * Custo por unidade-base.
     *
     * @param price custo e tamanho da embalagem
     * @return o custo por g, ml ou un
     */
    private static double unitCost(PriceState price) {
        return PricingEngine.unitCost(price.cost(), price.size());
    }
}
