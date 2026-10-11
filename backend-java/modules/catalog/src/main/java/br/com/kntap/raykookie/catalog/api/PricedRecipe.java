package br.com.kntap.raykookie.catalog.api;

import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Receita com o preço calculado: a linha no formato do Node ({@code RecipeRow}) e o cálculo.
 *
 * @param row     a receita como {@code getRecipes} devolve (com {@code items} e {@code photo_v})
 * @param pricing o cálculo, já com o preço fixado se houver
 */
public record PricedRecipe(Map<String, Object> row, PricingEngine.Result pricing) {

    /** Campos de {@code pricing}, na ordem do Node. */
    private static final List<String> PRICING_FIELDS = List.of("ingredients", "labor", "overhead", "total", "perUnit",
            "suggested", "rounded", "profitPerUnit", "profitTotal", "realMarginPct");

    /**
     * Id da receita.
     *
     * @return o id
     */
    public long id() {
        return ((Number) row.get("id")).longValue();
    }

    /**
     * Nome da receita.
     *
     * @return o nome
     */
    public String name() {
        return (String) row.get("name");
    }

    /**
     * Categoria da receita.
     *
     * @return o id da categoria, ou nulo
     */
    public Long categoryId() {
        Object id = row.get("category_id");
        return id == null ? null : ((Number) id).longValue();
    }

    /**
     * Se aparece no cardápio da loja.
     *
     * @return verdadeiro se aparece
     */
    public boolean showOnMenu() {
        return Boolean.TRUE.equals(row.get("show_on_menu"));
    }

    /**
     * Gramas de cada unidade (0 = não informado).
     *
     * @return o peso
     */
    public double unitWeightG() {
        return ((Number) row.get("unit_weight_g")).doubleValue();
    }

    /**
     * Selos do cardápio.
     *
     * @return os ids dos selos
     */
    @SuppressWarnings("unchecked")
    public List<String> tags() {
        return (List<String>) row.get("tags");
    }

    /**
     * Versão da foto (nulo = sem foto).
     *
     * @return a versão
     */
    public String photoVersion() {
        return (String) row.get("photo_v");
    }

    /**
     * Diz se o cardápio mostra a lista de ingredientes da receita padrão.
     *
     * @return verdadeiro se mostra
     */
    public boolean showIngredients() {
        return Boolean.TRUE.equals(row.get("show_ingredients"));
    }

    /**
     * Ingredientes da receita padrão, na ordem em que foram cadastrados.
     *
     * @return os ids
     */
    public List<Long> ingredientIds() {
        List<Long> ids = new ArrayList<>();
        if (row.get("items") instanceof JsonNode items) {
            for (JsonNode item : items) {
                ids.add(item.get("ingredient_id").asLong());
            }
        }
        return ids;
    }

    /**
     * A receita no formato do Node ({@code PricedRecipe}): a linha mais {@code pricing}.
     *
     * @return o mapa para o JSON
     */
    public Map<String, Object> json() {
        Map<String, Object> json = new LinkedHashMap<>(row);
        json.put("pricing", pricingJson(pricing));
        return json;
    }

    /**
     * O cálculo no formato do Node (inteiros sem ".0").
     *
     * @param result o cálculo
     * @return o mapa para o JSON
     */
    public static Map<String, Object> pricingJson(PricingEngine.Result result) {
        double[] values = {result.ingredients(), result.labor(), result.overhead(), result.total(), result.perUnit(),
            result.suggested(), result.rounded(), result.profitPerUnit(), result.profitTotal(), result.realMarginPct()};
        Map<String, Object> json = new LinkedHashMap<>();
        for (int i = 0; i < PRICING_FIELDS.size(); i++) {
            json.put(PRICING_FIELDS.get(i), NodeRows.number(values[i]));
        }
        return json;
    }
}
