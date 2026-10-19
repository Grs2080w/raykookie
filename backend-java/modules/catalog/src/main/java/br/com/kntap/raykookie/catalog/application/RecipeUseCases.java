package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.catalog.api.MenuTags;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
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

    /** Versões de receita (a padrão acompanha o formulário do produto). */
    private final RecipeVariantUseCases variants;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /** Campos da receita que o histórico guarda: tudo o que muda o custo, o rendimento ou o preço. */
    static final List<String> TRACKED = List.of("name", "category_id", "yield_units", "margin_pct", "prep_hours",
            "unit_weight_g", "sale_price", "items");

    /**
     * Cria os casos de uso.
     *
     * @param recipes     receitas
     * @param ingredients ingredientes
     * @param variants    versões de receita
     * @param audit       histórico de auditoria
     */
    public RecipeUseCases(Recipes recipes, Ingredients ingredients, RecipeVariantUseCases variants,
            AuditTrail audit) {
        this.recipes = recipes;
        this.ingredients = ingredients;
        this.variants = variants;
        this.audit = audit;
    }

    /**
     * Uma receita com os itens (os da versão padrão) e as versões ({@code variants}, com {@code is_default}).
     *
     * @param owner dona
     * @param id    receita
     * @return a receita
     * @throws NotFoundException se não for da dona
     */
    public Map<String, Object> get(OwnerId owner, long id) {
        Map<String, Object> row = new LinkedHashMap<>(recipes.list(owner).stream()
                .filter(r -> ((Number) r.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> new NotFoundException(NOT_FOUND)));
        row.put("variants", variants.withDefault(id));
        return row;
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
        Map<String, Object> was = id == null ? null : current(owner, id);
        Map<String, Object> row = id == null
                ? recipes.create(owner, form)
                : recipes.update(owner, id, form).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        long recipeId = ((Number) row.get("id")).longValue();
        recipes.replaceItems(recipeId, form.items());

        // ── 3. A versão padrão acompanha o que foi salvo (sem versão ainda, nasce a "Receita 1").
        variants.syncDefault(recipeId, form);

        // ── 4. Auditoria: a receita, e à parte o preço de venda quando ele mudou.
        Map<String, Object> now = current(owner, recipeId);
        audit.record(owner, Entry.of("receita", id == null ? "criou" : "editou", "recipe", recipeId,
                AuditValues.pick(was, TRACKED), AuditValues.pick(now, TRACKED),
                (id == null ? "Receita criada: " : "Receita editada: ") + row.get("name")));
        if (now != null && (was == null ? now.get("sale_price") != null
                : !AuditValues.same(was.get("sale_price"), now.get("sale_price")))) {
            audit.record(owner, Entry.of("preco", "editou", "recipe", recipeId,
                    AuditValues.pick(was, List.of("sale_price")), AuditValues.pick(now, List.of("sale_price")),
                    "Preço de venda de " + row.get("name") + ": "
                            + (was == null ? "sem preço" : AuditValues.brl(was.get("sale_price"))) + " para "
                            + AuditValues.brl(now.get("sale_price"))));
        }
        return row;
    }

    /**
     * A receita como está agora, com os itens (para o "antes" e o "depois" do histórico).
     *
     * @param owner dona
     * @param id    receita
     * @return a linha, ou {@code null} se não for da dona
     */
    private Map<String, Object> current(OwnerId owner, long id) {
        return recipes.list(owner).stream().filter(r -> ((Number) r.get("id")).longValue() == id).findFirst()
                .orElse(null);
    }

    /**
     * Apaga uma receita.
     *
     * @param owner dona
     * @param id    receita
     */
    @Transactional
    public void delete(OwnerId owner, long id) {
        Map<String, Object> was = current(owner, id);
        recipes.delete(owner, id);
        audit.record(owner, Entry.of("receita", "excluiu", "recipe", id, AuditValues.pick(was, TRACKED), null,
                "Receita excluída: " + (was == null ? "#" + id : was.get("name"))));
    }

    /**
     * Lista da página do Cardápio: o que aparece, se dá para aparecer (tem preço), o preço (o da receita padrão,
     * ou o fixado), a foto e se mostra os ingredientes.
     *
     * @param priced receitas da dona já precificadas
     * @return id, nome, visibilidade, selos, foto, preço e se mostra os ingredientes
     */
    public List<Map<String, Object>> menuEditor(List<PricedRecipe> priced) {
        return priced.stream().map(recipe -> {
            Map<String, Object> row = recipe.row();

            // ── 1. Campos do editor de cardápio (os de sempre).
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("name", row.get("name"));
            item.put("show_on_menu", row.get("show_on_menu"));
            item.put("tags", row.get("tags"));
            item.put("has_photo", row.get("photo_v") != null);
            item.put("priced", row.get("sale_price") != null || !RecipePricing.ingredientIds(row).isEmpty());

            // ── 2. Preço, foto e lista de ingredientes (página do Cardápio).
            item.put("price", recipe.pricing().rounded());
            item.put("photo_v", row.get("photo_v"));
            item.put("show_ingredients", Boolean.TRUE.equals(row.get("show_ingredients")));
            return item;
        }).toList();
    }

    /**
     * Liga/desliga a receita no cardápio e troca os selos.
     *
     * @param owner dona
     * @param id    receita
     * @param body  {@code show_on_menu} (booleano), {@code show_ingredients} (booleano) e/ou {@code tags} (lista)
     * @return {@code id}, {@code show_on_menu} e {@code tags}
     * @throws InvalidInputException sem nada para mudar
     * @throws NotFoundException     se a receita não for da dona
     */
    @Transactional
    public Map<String, Object> updateMenuFlags(OwnerId owner, long id, Map<String, Object> body) {

        // ── 1. Lê o que veio no corpo e recusa pedido sem nada para mudar.
        Boolean showOnMenu = body.get("show_on_menu") instanceof Boolean flag ? flag : null;
        Boolean showIngredients = body.get("show_ingredients") instanceof Boolean flag ? flag : null;
        List<String> tags = body.containsKey("tags") ? MenuTags.clean(body.get("tags")) : null;
        if (showOnMenu == null && tags == null && showIngredients == null) {
            throw new InvalidInputException("Nada para atualizar.");
        }

        // ── 2. Grava e registra o antes e o depois no histórico.
        List<String> flags = List.of("show_on_menu", "show_ingredients", "tags");
        Map<String, Object> was = current(owner, id);
        Map<String, Object> row = recipes.updateMenuFlags(owner, id, showOnMenu, tags, showIngredients)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        audit.record(owner, Entry.of("cardapio", "editou", "recipe", id, AuditValues.pick(was, flags),
                AuditValues.pick(current(owner, id), flags), "Cardápio: ajuste de " + row.get("name")
                        + (showOnMenu == null ? "" : showOnMenu ? " (passou a aparecer)" : " (saiu do cardápio)")));
        return row;
    }
}
