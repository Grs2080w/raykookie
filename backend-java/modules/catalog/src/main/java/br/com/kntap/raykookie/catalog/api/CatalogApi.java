package br.com.kntap.raykookie.catalog.api;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;

/**
 * O que o catálogo oferece aos outros módulos (cardápio da loja, painel, compras).
 */
public interface CatalogApi {

    /**
     * Receitas da dona já precificadas ({@code getPricedRecipes}).
     *
     * @param owner dona
     * @return as receitas, por nome
     */
    List<PricedRecipe> pricedRecipes(OwnerId owner);

    /**
     * Categorias da dona com a contagem de usos ({@code getCategories}).
     *
     * @param owner dona
     * @param kind  {@code ingredient}, {@code recipe} ou {@code expense}; nulo = todas
     * @return as categorias no formato do Node
     */
    List<Map<String, Object>> categories(OwnerId owner, String kind);

    /**
     * Ingredientes da dona ({@code getIngredients}).
     *
     * @param owner dona
     * @return as linhas de {@code ingredients} no formato do Node
     */
    List<Map<String, Object>> ingredients(OwnerId owner);

    /**
     * Depois de mudar o preço de um ingrediente: grava o histórico e cria os alertas de margem
     * ({@code onPriceChange}). Nunca lança erro: é um extra que não pode derrubar a edição.
     *
     * @param change a mudança
     * @return quantos alertas foram criados
     */
    int priceChanged(PriceChange change);
}
