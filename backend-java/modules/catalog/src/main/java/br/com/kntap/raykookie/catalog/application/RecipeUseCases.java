package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.MenuTags;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receitas da dona: as rotas {@code /api/recipes} e o editor de cardápio.
 */
@Service
public class RecipeUseCases {

    /** Mensagem de receita que não é da dona. */
    static final String NOT_FOUND = "Receita não encontrada.";

    /** Receitas. */
    private final Recipes recipes;

    /** Ingredientes (para conferir os itens). */
    private final Ingredients ingredients;

    /**
     * Cria os casos de uso.
     *
     * @param recipes     receitas
     * @param ingredients ingredientes
     */
    public RecipeUseCases(Recipes recipes, Ingredients ingredients) {
        this.recipes = recipes;
        this.ingredients = ingredients;
    }

    /**
     * Uma receita com os itens.
     *
     * @param owner dona
     * @param id    receita
     * @return a receita
     * @throws NotFoundException se não for da dona
     */
    public Map<String, Object> get(OwnerId owner, long id) {
        return recipes.list(owner).stream().filter(row -> ((Number) row.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    /**
     * Cria (id nulo) ou edita uma receita e troca os itens, numa transação ({@code saveRecipe}).
     *
     * @param owner dona
     * @param id    receita a editar; nulo = nova
     * @param body  corpo JSON
     * @return a linha gravada
     * @throws InvalidInputException com campo inválido ou ingrediente que não é da dona
     * @throws NotFoundException     se a receita não for da dona
     */
    @Transactional
    public Map<String, Object> save(OwnerId owner, Long id, Map<String, Object> body) {

        // ── 1. Valida o formulário e confere que todos os ingredientes são da dona.
        RecipeForm form = RecipeForm.from(body);
        List<Long> ids = form.ingredientIds();
        if (!ids.isEmpty() && ingredients.countOwned(owner, ids) != ids.size()) {
            throw new InvalidInputException("Algum ingrediente não foi encontrado no seu estoque.");
        }

        // ── 2. Grava a receita e troca os itens.
        Map<String, Object> row = id == null
                ? recipes.create(owner, form)
                : recipes.update(owner, id, form).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        recipes.replaceItems(((Number) row.get("id")).longValue(), form.items());
        return row;
    }

    /**
     * Apaga uma receita.
     *
     * @param owner dona
     * @param id    receita
     */
    public void delete(OwnerId owner, long id) {
        recipes.delete(owner, id);
    }

    /**
     * Lista enxuta para o editor de cardápio: o que aparece e se dá para aparecer (tem preço).
     *
     * @param owner dona
     * @return id, nome, visibilidade, selos, foto e se está precificada
     */
    public List<Map<String, Object>> menuEditor(OwnerId owner) {
        return recipes.list(owner).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("show_on_menu", row.get("show_on_menu"));
            item.put("tags", row.get("tags"));
            item.put("has_photo", row.get("photo_v") != null);
            item.put("priced", row.get("sale_price") != null || !RecipePricing.ingredientIds(row).isEmpty());
            return item;
        }).toList();
    }

    /**
     * Liga/desliga a receita no cardápio e troca os selos.
     *
     * @param owner dona
     * @param id    receita
     * @param body  {@code show_on_menu} (booleano) e/ou {@code tags} (lista)
     * @return {@code id}, {@code show_on_menu} e {@code tags}
     * @throws InvalidInputException sem nada para mudar
     * @throws NotFoundException     se a receita não for da dona
     */
    public Map<String, Object> updateMenuFlags(OwnerId owner, long id, Map<String, Object> body) {
        Boolean showOnMenu = body.get("show_on_menu") instanceof Boolean flag ? flag : null;
        List<String> tags = body.containsKey("tags") ? MenuTags.clean(body.get("tags")) : null;
        if (showOnMenu == null && tags == null) {
            throw new InvalidInputException("Nada para atualizar.");
        }
        return recipes.updateMenuFlags(owner, id, showOnMenu, tags)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }
}
