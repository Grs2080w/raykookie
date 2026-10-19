package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.purchasing.application.ports.Receipts;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import br.com.kntap.raykookie.web.NodeInput;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O lançamento da nota numa transação: registra a nota (trava de repetição), soma nos ingredientes escolhidos e
 * cria os novos. Qualquer item inválido desfaz tudo.
 */
@Service
public class ReceiptImporter {

    /** Maior quantidade de um item. */
    private static final double MAX_QTY = 100_000;

    /** Notas e estoque. */
    private final Receipts receipts;

    /** Catálogo (cadastro de ingredientes). */
    private final CatalogApi catalog;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria o serviço.
     *
     * @param receipts notas e estoque
     * @param catalog  catálogo
     * @param audit    histórico de auditoria
     */
    public ReceiptImporter(Receipts receipts, CatalogApi catalog, AuditTrail audit) {
        this.receipts = receipts;
        this.catalog = catalog;
        this.audit = audit;
    }

    /**
     * Lança a nota.
     *
     * @param owner dona
     * @param key   chave da nota
     * @param body  corpo (loja, data, total)
     * @param items itens já conferidos (1 a 300)
     * @return quantos foram somados e criados, e as mudanças de preço
     * @throws ConflictException     se a nota já foi lançada
     * @throws InvalidInputException com item inválido (nada é gravado)
     */
    @Transactional
    public Result apply(OwnerId owner, String key, Map<String, Object> body, List<?> items) {

        // ── 1. Registra a nota; se já existia, para aqui.
        String store = NodeInput.cut(NodeInput.text(body.get("store")), 80);
        Double total = body.get("total") == null ? null : NodeInput.number(body.get("total"));
        if (!receipts.register(owner, new Receipts.Note(key, store, isoDate(NodeInput.text(body.get("date"))),
                total))) {
            throw new ConflictException("Essa nota já foi lançada no estoque.");
        }

        // ── 2. Cada item: somar num ingrediente ou criar um novo.
        int added = 0;
        int created = 0;
        List<Change> changes = new ArrayList<>();
        for (Object raw : items) {
            Map<?, ?> item = raw instanceof Map<?, ?> map ? map : Map.of();
            Object action = item.get("action");
            if ("add".equals(action)) {
                add(owner, item, store).ifPresent(changes::add);
                added++;
            } else if ("create".equals(action)) {
                create(owner, item, store);
                created++;
            } else {
                throw new InvalidInputException("Item da nota em formato desconhecido.");
            }
        }

        // ── 3. Guarda o resumo.
        receipts.finish(owner, key, added, created);
        return new Result(added, created, changes);
    }

    /**
     * Soma no estoque, troca o custo e lembra o apelido.
     *
     * @param owner dona
     * @param item  item da nota
     * @param store loja da nota
     * @return a mudança de preço, se o ingrediente tinha preço antes
     */
    private Optional<Change> add(OwnerId owner, Map<?, ?> item, String store) {

        // ── 1. Quantidade e custo.
        double qty = NodeInput.number(item.get("add_qty"));
        if (qty <= 0 || qty > MAX_QTY) {
            throw new InvalidInputException("A quantidade de um dos itens não é válida.");
        }
        Double cost = item.get("package_cost") == null ? null : NodeInput.number(item.get("package_cost"));

        // ── 2. Soma no ingrediente (que precisa continuar existindo).
        long id = NodeInput.positiveId(item.get("ingredient_id")).orElse(0L);
        Optional<PriceState> before = receipts.price(owner, id);
        PriceState after = receipts.addStock(owner, id, qty, cost).orElseThrow(() -> new InvalidInputException(
                "Um dos ingredientes escolhidos não existe mais. Leia a nota de novo."));

        // ── 3. Apelido do item.
        String alias = String.join(" ", NfceMatching.nameTokens(NodeInput.text(item.get("item_name"))));
        if (!alias.isEmpty()) {
            receipts.remember(owner, alias, id);
        }

        // ── 4. Auditoria: a entrada no estoque e, se o custo mudou, o preço.
        String name = NodeInput.text(item.get("item_name"));
        audit.record(owner, Entry.of("estoque", "importou", "ingredient", id,
                before.map(old -> Map.<String, Object>of("package_cost", old.cost(), "package_size", old.size()))
                        .orElse(null), Map.<String, Object>of("added_qty", qty, "package_cost", after.cost(),
                                "package_size", after.size(), "store", store),
                "Entrada por nota fiscal (" + store + "): +" + AuditValues.num(qty) + " de " + name));
        before.filter(old -> !old.equals(after)).ifPresent(old -> audit.record(owner, Entry.of("preco", "importou",
                "ingredient", id, Map.of("package_cost", old.cost(), "package_size", old.size()),
                Map.of("package_cost", after.cost(), "package_size", after.size()), "Preço de " + name
                        + " pela nota fiscal: R$ " + AuditValues.num(old.cost()) + " para R$ "
                        + AuditValues.num(after.cost()))));
        return before.map(old -> new Change(id, old, after));
    }

    /**
     * Cria o ingrediente sugerido.
     *
     * @param owner dona
     * @param item  item da nota
     * @param store loja (vira o fornecedor)
     */
    private void create(OwnerId owner, Map<?, ?> item, String store) {

        // ── 1. Monta o formulário do ingrediente com os dados da nota.
        Map<String, Object> form = new HashMap<>();
        item.forEach((name, value) -> form.put(String.valueOf(name), value));
        form.put("min_stock", 0);
        form.put("supplier", store);
        form.put("notes", "Criado pela leitura de nota fiscal.");

        // ── 2. Cria o ingrediente e registra a entrada no histórico.
        Map<String, Object> row = catalog.createIngredient(owner, IngredientForm.from(form));
        audit.record(owner, Entry.of("estoque", "importou", "ingredient", row.get("id"), null,
                Map.of("name", String.valueOf(row.get("name")), "stock_qty", String.valueOf(row.get("stock_qty")),
                        "store", store), "Ingrediente criado pela nota fiscal (" + store + "): " + row.get("name")));
    }

    /**
     * Data {@code AAAA-MM-DD} que existe.
     *
     * @param text texto
     * @return a data, ou nula
     */
    static String isoDate(String text) {
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Mudança de preço de um ingrediente.
     *
     * @param ingredientId ingrediente
     * @param before       antes
     * @param after        depois
     */
    public record Change(long ingredientId, PriceState before, PriceState after) {
    }

    /**
     * Resultado do lançamento.
     *
     * @param added   somados
     * @param created criados
     * @param changes mudanças de preço
     */
    public record Result(int added, int created, List<Change> changes) {
    }
}
