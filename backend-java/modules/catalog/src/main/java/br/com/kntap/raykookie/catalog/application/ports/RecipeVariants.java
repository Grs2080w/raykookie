package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versões de receita de cada produto (tabelas {@code recipe_variants} e {@code recipe_variant_items}). A padrão é
 * copiada para {@code recipes}/{@code recipe_items}, que é o que o preço, os combos e as métricas leem.
 */
public interface RecipeVariants {

    /**
     * Versões de um produto, na ordem das abas, com os ingredientes.
     *
     * @param recipeId produto
     * @return {@code id}, {@code name}, {@code position}, {@code yield_units}, {@code prep_hours} e {@code items}
     */
    List<Map<String, Object>> ofRecipe(long recipeId);

    /**
     * Versão padrão do produto.
     *
     * @param recipeId produto
     * @return a versão, ou vazio se ainda não houver
     */
    Optional<Long> defaultOf(long recipeId);

    /**
     * Quantas versões o produto tem.
     *
     * @param recipeId produto
     * @return o total
     */
    int count(long recipeId);

    /**
     * Diz se a versão é do produto.
     *
     * @param recipeId  produto
     * @param variantId versão
     * @return verdadeiro se for
     */
    boolean belongs(long recipeId, long variantId);

    /**
     * Cria uma versão no fim das abas (sem ingredientes).
     *
     * @param recipeId   produto
     * @param name       nome
     * @param yieldUnits rendimento
     * @param prepHours  preparo
     * @return o id
     */
    long create(long recipeId, String name, double yieldUnits, double prepHours);

    /**
     * Troca nome, rendimento e preparo.
     *
     * @param variantId  versão
     * @param name       nome
     * @param yieldUnits rendimento
     * @param prepHours  preparo
     */
    void update(long variantId, String name, double yieldUnits, double prepHours);

    /**
     * Troca os ingredientes.
     *
     * @param variantId versão
     * @param items     ingredientes
     */
    void replaceItems(long variantId, List<RecipeForm.Item> items);

    /**
     * Copia os ingredientes de outra versão.
     *
     * @param from versão de origem
     * @param to   versão de destino
     */
    void copyItems(long from, long to);

    /**
     * Apaga uma versão.
     *
     * @param variantId versão
     */
    void delete(long variantId);

    /**
     * Marca a versão padrão e copia rendimento, preparo e ingredientes dela para o produto.
     *
     * @param recipeId  produto
     * @param variantId versão
     */
    void makeDefault(long recipeId, long variantId);
}
