package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.RecipeVariants;
import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.catalog.domain.VariantForm;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versões de receita de um produto (abas Receita 1, Receita 2…): criar, salvar, excluir e escolher a padrão. A
 * padrão é copiada para o produto, que é o que o cardápio, os combos e as métricas usam.
 */
@Service
public class RecipeVariantUseCases {

    /** Máximo de versões por produto. */
    static final int MAX_VARIANTS = 10;

    /** Versão que não é do produto. */
    static final String NOT_FOUND = "Receita não encontrada neste produto.";

    /** Receitas (produtos). */
    private final Recipes recipes;

    /** Versões. */
    private final RecipeVariants variants;

    /** Ingredientes (para conferir os itens). */
    private final Ingredients ingredients;

    /**
     * Cria os casos de uso.
     *
     * @param recipes     receitas
     * @param variants    versões
     * @param ingredients ingredientes
     */
    public RecipeVariantUseCases(Recipes recipes, RecipeVariants variants, Ingredients ingredients) {
        this.recipes = recipes;
        this.variants = variants;
        this.ingredients = ingredients;
    }

    /**
     * Versões do produto, com a padrão marcada.
     *
     * @param owner    dona
     * @param recipeId produto
     * @return as versões
     * @throws NotFoundException se o produto não for da dona
     */
    public List<Map<String, Object>> list(OwnerId owner, long recipeId) {
        requireRecipe(owner, recipeId);
        return withDefault(recipeId);
    }

    /**
     * Versões do produto com {@code is_default} (sem conferir a dona: quem chama já conferiu).
     *
     * @param recipeId produto
     * @return as versões
     */
    List<Map<String, Object>> withDefault(long recipeId) {
        Optional<Long> standard = variants.defaultOf(recipeId);
        return variants.ofRecipe(recipeId).stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>(row);
            view.put("is_default", standard.isPresent()
                    && standard.get() == ((Number) row.get("id")).longValue());
            return view;
        }).toList();
    }

    /**
     * Nova versão (aba "+"): vazia, ou cópia de outra versão do produto.
     *
     * @param owner    dona
     * @param recipeId produto
     * @param body     {@code name} (opcional) e {@code copy_from} (opcional: id da versão a copiar)
     * @return as versões do produto
     * @throws InvalidInputException no limite de versões ou com cópia de versão de outro produto
     */
    @Transactional
    public List<Map<String, Object>> create(OwnerId owner, long recipeId, Map<String, Object> body) {

        // ── 1. Produto da dona e dentro do limite.
        requireRecipe(owner, recipeId);
        int count = variants.count(recipeId);
        if (count >= MAX_VARIANTS) {
            throw new InvalidInputException("Cada produto pode ter até " + MAX_VARIANTS + " receitas.");
        }

        // ── 2. Nome e origem da cópia.
        String name = NodeInput.cut(NodeInput.text(body.get("name")), 40);
        Optional<Long> copyFrom = NodeInput.positiveId(body.get("copy_from"));
        if (copyFrom.isPresent() && !variants.belongs(recipeId, copyFrom.get())) {
            throw new InvalidInputException(NOT_FOUND);
        }

        // ── 3. Cria e copia os ingredientes (e rendimento/preparo) da origem.
        Map<String, Object> source = copyFrom.flatMap(id -> variants.ofRecipe(recipeId).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == id).findFirst()).orElse(Map.of());
        long id = variants.create(recipeId, name.isEmpty() ? "Receita " + (count + 1) : name,
                number(source.get("yield_units"), 1), number(source.get("prep_hours"), 0));
        copyFrom.ifPresent(from -> variants.copyItems(from, id));
        return withDefault(recipeId);
    }

    /**
     * Salva uma versão; sendo a padrão, o produto passa a usar os dados novos.
     *
     * @param owner     dona
     * @param recipeId  produto
     * @param variantId versão
     * @param body      {@code name}, {@code yield_units}, {@code prep_hours} e {@code items}
     * @return as versões do produto
     * @throws InvalidInputException com campo inválido ou ingrediente que não é da dona
     */
    @Transactional
    public List<Map<String, Object>> save(OwnerId owner, long recipeId, long variantId, Map<String, Object> body) {

        // ── 1. Versão do produto da dona e formulário válido.
        requireVariant(owner, recipeId, variantId);
        VariantForm form = VariantForm.from(body);
        requireIngredients(owner, form.ingredientIds());

        // ── 2. Grava; a padrão é copiada para o produto.
        variants.update(variantId, form.name(), form.yieldUnits(), form.prepHours());
        variants.replaceItems(variantId, form.items());
        if (variants.defaultOf(recipeId).filter(standard -> standard == variantId).isPresent()) {
            variants.makeDefault(recipeId, variantId);
        }
        return withDefault(recipeId);
    }

    /**
     * Exclui uma versão (a padrão não pode).
     *
     * @param owner     dona
     * @param recipeId  produto
     * @param variantId versão
     * @return as versões do produto
     * @throws InvalidInputException se for a padrão
     */
    @Transactional
    public List<Map<String, Object>> delete(OwnerId owner, long recipeId, long variantId) {
        requireVariant(owner, recipeId, variantId);
        if (variants.defaultOf(recipeId).filter(standard -> standard == variantId).isPresent()) {
            throw new InvalidInputException("Esta é a receita padrão. Escolha outra como padrão antes de excluir.");
        }
        variants.delete(variantId);
        return withDefault(recipeId);
    }

    /**
     * Escolhe a versão padrão: cardápio, combos, lucro e metas passam a usar ela.
     *
     * @param owner     dona
     * @param recipeId  produto
     * @param variantId versão
     * @return as versões do produto
     */
    @Transactional
    public List<Map<String, Object>> makeDefault(OwnerId owner, long recipeId, long variantId) {
        requireVariant(owner, recipeId, variantId);
        variants.makeDefault(recipeId, variantId);
        return withDefault(recipeId);
    }

    /**
     * Mantém a versão padrão igual ao produto salvo pelo formulário da receita (apps antigos e a criação do
     * produto): sem padrão ainda, cria a "Receita 1".
     *
     * @param recipeId produto
     * @param form     o formulário salvo
     */
    void syncDefault(long recipeId, RecipeForm form) {
        Optional<Long> standard = variants.defaultOf(recipeId);
        long id = standard.orElseGet(() -> variants.create(recipeId, "Receita 1", form.yieldUnits(),
                form.prepHours()));
        String name = standard.isPresent() ? variants.ofRecipe(recipeId).stream()
                .filter(row -> ((Number) row.get("id")).longValue() == id).map(row -> String.valueOf(row.get("name")))
                .findFirst().orElse("Receita 1") : "Receita 1";
        variants.update(id, name, form.yieldUnits(), form.prepHours());
        variants.replaceItems(id, form.items());
        if (standard.isEmpty()) {
            variants.makeDefault(recipeId, id);
        }
    }

    /**
     * Confere que o produto é da dona.
     *
     * @param owner    dona
     * @param recipeId produto
     * @throws NotFoundException se não for
     */
    private void requireRecipe(OwnerId owner, long recipeId) {
        if (!recipes.exists(owner, recipeId)) {
            throw new NotFoundException(RecipeUseCases.NOT_FOUND);
        }
    }

    /**
     * Confere que a versão é do produto da dona.
     *
     * @param owner     dona
     * @param recipeId  produto
     * @param variantId versão
     * @throws NotFoundException se não for
     */
    private void requireVariant(OwnerId owner, long recipeId, long variantId) {
        requireRecipe(owner, recipeId);
        if (!variants.belongs(recipeId, variantId)) {
            throw new NotFoundException(NOT_FOUND);
        }
    }

    /**
     * Confere que todos os ingredientes são da dona.
     *
     * @param owner dona
     * @param ids   ingredientes
     * @throws InvalidInputException se algum não for
     */
    private void requireIngredients(OwnerId owner, List<Long> ids) {
        if (!ids.isEmpty() && ingredients.countOwned(owner, ids) != ids.size()) {
            throw new InvalidInputException("Algum ingrediente não foi encontrado no seu estoque.");
        }
    }

    /**
     * Número de uma coluna (ausente = o padrão).
     *
     * @param raw      valor
     * @param fallback padrão
     * @return o número
     */
    private static double number(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }
}
