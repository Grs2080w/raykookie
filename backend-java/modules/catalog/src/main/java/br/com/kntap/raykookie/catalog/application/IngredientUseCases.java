package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.web.NodeInput;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Ingredientes da dona: as rotas {@code /api/ingredients}.
 */
@Service
public class IngredientUseCases {

    /** Mensagem de ingrediente que não é da dona. */
    static final String NOT_FOUND = "Ingrediente não encontrado.";

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Histórico e alertas de preço. */
    private final PriceImpact priceImpact;

    /**
     * Cria os casos de uso.
     *
     * @param ingredients ingredientes
     * @param priceImpact histórico e alertas de preço
     */
    public IngredientUseCases(Ingredients ingredients, PriceImpact priceImpact) {
        this.ingredients = ingredients;
        this.priceImpact = priceImpact;
    }

    /**
     * Lista os ingredientes.
     *
     * @param owner dona
     * @return os ingredientes, por nome
     */
    public List<Map<String, Object>> list(OwnerId owner) {
        return ingredients.list(owner);
    }

    /**
     * Cria um ingrediente.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada
     */
    public Map<String, Object> create(OwnerId owner, Map<String, Object> body) {
        return ingredients.create(owner, IngredientForm.from(body));
    }

    /**
     * Edita um ingrediente: só o estoque, ou tudo (e aí registra a mudança de preço, depois de gravar).
     *
     * @param owner dona
     * @param id    ingrediente
     * @param body  corpo JSON
     * @return a linha editada
     * @throws NotFoundException se o ingrediente não for da dona
     */
    public Map<String, Object> update(OwnerId owner, long id, Map<String, Object> body) {

        // ── 1. Edição rápida só do estoque.
        if (IngredientForm.isStockOnly(body)) {
            return ingredients.updateStock(owner, id, NodeInput.number(body.get("stock_qty")))
                    .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        }

        // ── 2. Edição completa, guardando o preço de antes.
        IngredientForm form = IngredientForm.from(body);
        Optional<PriceState> before = ingredients.price(owner, id);
        Map<String, Object> row = ingredients.update(owner, id, form)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));

        // ── 3. Histórico e alertas de margem, com o preço como ficou no banco.
        PriceState after = new PriceState(((Number) row.get("package_cost")).doubleValue(),
                ((Number) row.get("package_size")).doubleValue());
        before.ifPresent(old -> priceImpact.priceChanged(new PriceChange(owner, id, old, after, "edicao", null)));
        return row;
    }

    /**
     * Apaga um ingrediente que nenhuma receita usa.
     *
     * @param owner dona
     * @param id    ingrediente
     * @throws ConflictException se alguma receita o usa
     */
    public void delete(OwnerId owner, long id) {
        int used = ingredients.usedInRecipes(owner, id);
        if (used > 0) {
            throw new ConflictException("Este ingrediente é usado em " + used
                    + " receita(s). Remova-o das receitas antes de excluir.");
        }
        ingredients.delete(owner, id);
    }
}
