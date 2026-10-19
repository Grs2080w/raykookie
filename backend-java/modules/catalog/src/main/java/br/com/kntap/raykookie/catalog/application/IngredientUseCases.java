package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.web.NodeInput;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingredientes da dona: as rotas {@code /api/ingredients}. Toda mudança de estoque ou de preço vai para o histórico de
 * auditoria, na mesma transação.
 */
@Service
public class IngredientUseCases {

    /** Mensagem de ingrediente que não é da dona. */
    static final String NOT_FOUND = "Ingrediente não encontrado.";

    /** Campos do ingrediente que o histórico guarda (o resto da linha não muda o estoque nem o custo). */
    private static final List<String> TRACKED = List.of("name", "unit", "stock_qty", "package_size", "package_cost",
            "min_stock", "supplier", "category_id");

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Histórico e alertas de preço. */
    private final PriceImpact priceImpact;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria os casos de uso.
     *
     * @param ingredients ingredientes
     * @param priceImpact histórico e alertas de preço
     * @param audit       histórico de auditoria
     */
    public IngredientUseCases(Ingredients ingredients, PriceImpact priceImpact, AuditTrail audit) {
        this.ingredients = ingredients;
        this.priceImpact = priceImpact;
        this.audit = audit;
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
    @Transactional
    public Map<String, Object> create(OwnerId owner, Map<String, Object> body) {
        Map<String, Object> row = ingredients.create(owner, IngredientForm.from(body));
        audit.record(owner, Entry.of("estoque", "criou", "ingredient", row.get("id"), null, snapshot(row),
                "Ingrediente criado: " + row.get("name") + " (estoque " + AuditValues.num(row.get("stock_qty")) + " "
                        + row.get("unit") + ")"));
        return row;
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
    @Transactional
    public Map<String, Object> update(OwnerId owner, long id, Map<String, Object> body) {

        // ── 1. Como o ingrediente está agora (o "antes" do histórico).
        Map<String, Object> was = current(owner, id);

        // ── 2. Edição rápida só do estoque.
        if (IngredientForm.isStockOnly(body)) {
            Map<String, Object> row = ingredients.updateStock(owner, id, NodeInput.number(body.get("stock_qty")))
                    .orElseThrow(() -> new NotFoundException(NOT_FOUND));
            audit.record(owner, Entry.of("estoque", "ajustou", "ingredient", id, snapshot(was), snapshot(row),
                    "Estoque de " + row.get("name") + ": " + AuditValues.num(was == null ? null : was.get("stock_qty"))
                            + " para " + AuditValues.num(row.get("stock_qty")) + " " + row.get("unit")));
            return row;
        }

        // ── 3. Edição completa, guardando o preço de antes.
        IngredientForm form = IngredientForm.from(body);
        Optional<PriceState> before = ingredients.price(owner, id);
        Map<String, Object> row = ingredients.update(owner, id, form)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));

        // ── 4. Histórico e alertas de margem, com o preço como ficou no banco.
        PriceState after = new PriceState(((Number) row.get("package_cost")).doubleValue(),
                ((Number) row.get("package_size")).doubleValue());
        before.ifPresent(old -> priceImpact.priceChanged(new PriceChange(owner, id, old, after, "edicao", null)));

        // ── 5. Auditoria: a edição, e à parte a mudança de preço do pacote (muda o custo das receitas).
        audit.record(owner, Entry.of("estoque", "editou", "ingredient", id, snapshot(was), snapshot(row),
                "Ingrediente editado: " + row.get("name")));
        if (was != null && (!AuditValues.same(was.get("package_cost"), row.get("package_cost"))
                || !AuditValues.same(was.get("package_size"), row.get("package_size")))) {
            audit.record(owner, Entry.of("preco", "editou", "ingredient", id, priceOf(was), priceOf(row),
                    "Preço de " + row.get("name") + ": R$ " + AuditValues.num(was.get("package_cost")) + " por "
                            + AuditValues.num(was.get("package_size")) + " " + row.get("unit") + " para R$ "
                            + AuditValues.num(row.get("package_cost")) + " por "
                            + AuditValues.num(row.get("package_size")) + " " + row.get("unit")));
        }
        return row;
    }

    /**
     * Apaga um ingrediente que nenhuma receita usa.
     *
     * @param owner dona
     * @param id    ingrediente
     * @throws ConflictException se alguma receita o usa
     */
    @Transactional
    public void delete(OwnerId owner, long id) {
        int used = ingredients.usedInRecipes(owner, id);
        if (used > 0) {
            throw new ConflictException("Este ingrediente é usado em " + used
                    + " receita(s). Remova-o das receitas antes de excluir.");
        }
        Map<String, Object> was = current(owner, id);
        ingredients.delete(owner, id);
        audit.record(owner, Entry.of("estoque", "excluiu", "ingredient", id, snapshot(was), null,
                "Ingrediente excluído: " + (was == null ? "#" + id : was.get("name"))));
    }

    /**
     * A linha do ingrediente como está agora (para o "antes" do histórico).
     *
     * @param owner dona
     * @param id    ingrediente
     * @return a linha, ou {@code null} se não for da dona
     */
    private Map<String, Object> current(OwnerId owner, long id) {
        return ingredients.list(owner).stream().filter(row -> row.get("id") instanceof Number n && n.longValue() == id)
                .findFirst().orElse(null);
    }

    /**
     * Só os campos que o histórico guarda.
     *
     * @param row linha do ingrediente
     * @return a cópia, ou {@code null}
     */
    private static Map<String, Object> snapshot(Map<String, Object> row) {
        return AuditValues.pick(row, TRACKED);
    }

    /**
     * O preço do pacote.
     *
     * @param row linha do ingrediente
     * @return custo e tamanho do pacote
     */
    private static Map<String, Object> priceOf(Map<String, Object> row) {
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("package_cost", row.get("package_cost"));
        price.put("package_size", row.get("package_size"));
        return price;
    }
}
