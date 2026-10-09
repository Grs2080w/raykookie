package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.catalog.application.ports.FixedCosts;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.OwnerSettings;
import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Preço das receitas da dona ({@code getPricedRecipes} do Node): junta receitas, custos dos ingredientes,
 * ajustes e gastos fixos e passa pelo {@link PricingEngine}.
 */
@Service
public class RecipePricing {

    /** Receitas. */
    private final Recipes recipes;

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Ajustes. */
    private final OwnerSettings settings;

    /** Gastos fixos. */
    private final FixedCosts fixedCosts;

    /**
     * Cria o serviço.
     *
     * @param recipes     receitas
     * @param ingredients ingredientes
     * @param settings    ajustes
     * @param fixedCosts  gastos fixos
     */
    public RecipePricing(Recipes recipes, Ingredients ingredients, OwnerSettings settings, FixedCosts fixedCosts) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.settings = settings;
        this.fixedCosts = fixedCosts;
    }

    /**
     * Receitas da dona com o preço calculado (já com o preço fixado, se houver).
     *
     * @param owner dona
     * @return as receitas, por nome
     */
    public List<PricedRecipe> priced(OwnerId owner) {
        Context context = context(owner);
        List<PricedRecipe> priced = new ArrayList<>();
        for (Map<String, Object> row : context.rows()) {
            PricingEngine.Result result = PricingEngine.calculate(input(row, context.costs(), context));
            priced.add(new PricedRecipe(row, PricingEngine.withSalePrice(result, salePrice(row))));
        }
        return priced;
    }

    /**
     * Tudo o que o cálculo precisa, lido de uma vez.
     *
     * @param owner dona
     * @return receitas, custos, ajustes e gastos
     */
    Context context(OwnerId owner) {
        return new Context(recipes.list(owner), ingredients.unitCosts(owner),
                settings.find(owner).orElse(OwnerSettings.Values.DEFAULTS), fixedCosts.total(owner));
    }

    /**
     * Entrada do cálculo de uma receita, com os custos informados.
     *
     * @param row     a receita (linha de {@code getRecipes})
     * @param costs   custo por unidade-base de cada ingrediente
     * @param context ajustes e gastos
     * @return a entrada
     */
    static PricingEngine.Input input(Map<String, Object> row, Map<Long, Double> costs, Context context) {
        List<PricingEngine.Item> items = new ArrayList<>();
        for (JsonNode item : (JsonNode) row.get("items")) {
            double cost = costs.getOrDefault(item.get("ingredient_id").asLong(), 0.0);
            items.add(new PricingEngine.Item(item.get("qty").asDouble(), cost));
        }
        return new PricingEngine.Input(items, number(row, "yield_units"), number(row, "margin_pct"),
                number(row, "prep_hours"), context.settings().hourlyRate(), context.fixedTotal(),
                context.settings().monthlyUnits());
    }

    /**
     * Ids dos ingredientes de uma receita.
     *
     * @param row a receita
     * @return os ids, na ordem dos itens
     */
    static List<Long> ingredientIds(Map<String, Object> row) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : (JsonNode) row.get("items")) {
            ids.add(item.get("ingredient_id").asLong());
        }
        return ids;
    }

    /**
     * Preço fixado da receita.
     *
     * @param row a receita
     * @return o preço, ou nulo
     */
    static Double salePrice(Map<String, Object> row) {
        Object sale = row.get("sale_price");
        return sale == null ? null : ((Number) sale).doubleValue();
    }

    /**
     * Coluna numérica da linha.
     *
     * @param row    a linha
     * @param column a coluna
     * @return o valor
     */
    private static double number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    /**
     * Dados do cálculo.
     *
     * @param rows       receitas
     * @param costs      custo por unidade-base de cada ingrediente
     * @param settings   ajustes
     * @param fixedTotal soma dos gastos do mês
     */
    record Context(List<Map<String, Object>> rows, Map<Long, Double> costs, OwnerSettings.Values settings,
            double fixedTotal) {
    }
}
