package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.catalog.domain.IngredientForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ingredientes da dona (tabela {@code ingredients}).
 */
public interface Ingredients {

    /**
     * Todos os ingredientes, por nome.
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Cria um ingrediente.
     *
     * @param owner dona
     * @param form  dados
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, IngredientForm form);

    /**
     * Edita um ingrediente da dona.
     *
     * @param owner dona
     * @param id    ingrediente
     * @param form  dados
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> update(OwnerId owner, long id, IngredientForm form);

    /**
     * Troca só o estoque.
     *
     * @param owner    dona
     * @param id       ingrediente
     * @param stockQty estoque novo
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> updateStock(OwnerId owner, long id, double stockQty);

    /**
     * Custo e tamanho atuais da embalagem.
     *
     * @param owner dona
     * @param id    ingrediente
     * @return o preço, ou vazio se não for da dona
     */
    Optional<PriceState> price(OwnerId owner, long id);

    /**
     * Nome e unidade, para o texto do alerta.
     *
     * @param owner dona
     * @param id    ingrediente
     * @return nome e unidade, ou vazio se não for da dona
     */
    Optional<NameAndUnit> nameAndUnit(OwnerId owner, long id);

    /**
     * Marca a data da última mudança de preço.
     *
     * @param id ingrediente
     */
    void markPriceUpdated(long id);

    /**
     * Em quantas receitas da dona o ingrediente é usado.
     *
     * @param owner dona
     * @param id    ingrediente
     * @return o número de receitas (itens)
     */
    int usedInRecipes(OwnerId owner, long id);

    /**
     * Apaga um ingrediente da dona.
     *
     * @param owner dona
     * @param id    ingrediente
     */
    void delete(OwnerId owner, long id);

    /**
     * Quantos destes ingredientes são da dona.
     *
     * @param owner dona
     * @param ids   ids distintos
     * @return quantos são dela
     */
    int countOwned(OwnerId owner, Collection<Long> ids);

    /**
     * Custo por unidade-base de cada ingrediente da dona.
     *
     * @param owner dona
     * @return id → custo por g, ml ou un
     */
    Map<Long, Double> unitCosts(OwnerId owner);

    /**
     * Grava uma linha no histórico de preços ({@code price_quotes}).
     *
     * @param owner        dona
     * @param ingredientId ingrediente
     * @param supplierId   fornecedor (nulo = nenhum)
     * @param price        custo e tamanho
     * @param unit         unidade do ingrediente
     * @param source       origem
     */
    void recordQuote(OwnerId owner, long ingredientId, Long supplierId, PriceState price, String unit, String source);

    /**
     * Nome e unidade de um ingrediente.
     *
     * @param name nome
     * @param unit {@code g}, {@code ml} ou {@code un}
     */
    record NameAndUnit(String name, String unit) {
    }
}
