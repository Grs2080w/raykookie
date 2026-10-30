package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Estoque de produção (unidades prontas por receita) e histórico de produção. As escritas rodam dentro da transação
 * do caso de uso; as travas ({@code lock*}) valem até o fim dela.
 */
public interface ProductionStore {

    /**
     * Unidades prontas de cada receita da dona (receita sem linha = 0).
     *
     * @param owner dona
     * @return quantidade por id de receita
     */
    Map<Long, BigDecimal> recipeStock(OwnerId owner);

    /**
     * Trava e lê o estoque de cada ingrediente (em ordem de id, para duas produções não se travarem).
     *
     * @param owner dona
     * @param ids   ingredientes
     * @return estoque por id de ingrediente
     */
    Map<Long, BigDecimal> lockIngredientStock(OwnerId owner, Collection<Long> ids);

    /**
     * Grava o estoque de um ingrediente.
     *
     * @param owner dona
     * @param id    ingrediente
     * @param qty   estoque novo
     */
    void setIngredientStock(OwnerId owner, long id, BigDecimal qty);

    /**
     * Trava e lê as unidades prontas de uma receita da dona.
     *
     * @param owner    dona
     * @param recipeId receita
     * @return a quantidade (0 se ainda não há linha)
     */
    BigDecimal lockRecipeStock(OwnerId owner, long recipeId);

    /**
     * Grava as unidades prontas de uma receita.
     *
     * @param owner    dona
     * @param recipeId receita
     * @param qty      quantidade nova
     */
    void setRecipeStock(OwnerId owner, long recipeId, BigDecimal qty);

    /**
     * Grava uma produção.
     *
     * @param owner    dona
     * @param recipeId receita
     * @param recipe   nome da receita
     * @param qty      unidades produzidas
     * @param cost     custo total
     * @param consumed JSON dos ingredientes consumidos
     * @param forced   se foi confirmada com ingrediente faltando
     * @return a linha criada
     */
    Map<String, Object> insertRun(OwnerId owner, long recipeId, String recipe, BigDecimal qty, BigDecimal cost,
            String consumed, boolean forced);

    /**
     * Produções da dona, mais novas primeiro.
     *
     * @param owner    dona
     * @param recipeId filtro por receita (nulo = todas)
     * @param from     a partir deste dia, no fuso da loja (nulo = sem limite)
     * @param to       até este dia, no fuso da loja (nulo = sem limite)
     * @return as linhas
     */
    List<Map<String, Object>> runs(OwnerId owner, Long recipeId, LocalDate from, LocalDate to);

    /**
     * Trava e lê uma produção da dona.
     *
     * @param owner dona
     * @param id    produção
     * @return a linha, ou vazio
     */
    Optional<Map<String, Object>> lockRun(OwnerId owner, long id);

    /**
     * Marca a produção como desfeita agora.
     *
     * @param id produção
     * @return a linha atualizada
     */
    Map<String, Object> markUndone(long id);
}
