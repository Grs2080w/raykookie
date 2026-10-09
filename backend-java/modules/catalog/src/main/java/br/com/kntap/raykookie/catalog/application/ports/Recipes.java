package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receitas da dona (tabelas {@code recipes} e {@code recipe_items}).
 */
public interface Recipes {

    /**
     * Receitas com itens e versão da foto ({@code getRecipes}), por nome.
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Cria uma receita (sem os itens).
     *
     * @param owner dona
     * @param form  dados
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, RecipeForm form);

    /**
     * Edita uma receita da dona (sem os itens).
     *
     * @param owner dona
     * @param id    receita
     * @param form  dados
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> update(OwnerId owner, long id, RecipeForm form);

    /**
     * Troca os itens de uma receita.
     *
     * @param recipeId receita
     * @param items    itens novos
     */
    void replaceItems(long recipeId, List<RecipeForm.Item> items);

    /**
     * Apaga uma receita da dona.
     *
     * @param owner dona
     * @param id    receita
     */
    void delete(OwnerId owner, long id);

    /**
     * Liga/desliga a visibilidade e troca os selos ({@code null} = mantém).
     *
     * @param owner      dona
     * @param id         receita
     * @param showOnMenu visibilidade nova
     * @param tags       selos novos
     * @return {@code id}, {@code show_on_menu} e {@code tags}, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> updateMenuFlags(OwnerId owner, long id, Boolean showOnMenu, List<String> tags);

    /**
     * Diz se a receita é da dona.
     *
     * @param owner dona
     * @param id    receita
     * @return verdadeiro se for
     */
    boolean exists(OwnerId owner, long id);

    /**
     * Fixa o preço do cardápio.
     *
     * @param owner     dona
     * @param id        receita
     * @param salePrice preço
     */
    void setSalePrice(OwnerId owner, long id, double salePrice);
}
