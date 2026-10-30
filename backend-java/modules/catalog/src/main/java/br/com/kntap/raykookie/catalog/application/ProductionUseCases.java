package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.ProductionStore;
import br.com.kntap.raykookie.catalog.domain.ProductionPlan;
import br.com.kntap.raykookie.catalog.domain.ProductionPlan.Line;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controle de produção: o estoque de unidades prontas por receita, o registro de uma produção (que desconta os
 * ingredientes e soma as unidades, tudo na mesma transação) e o histórico com "desfazer".
 *
 * <p>Pendente: abater as unidades vendidas quando um pedido é entregue. Hoje a dona acerta o estoque de produção
 * à mão ({@code PATCH /api/production/stock/{receita}}); ligar com os Pedidos mexeria no fluxo de entrega, então
 * ficou de fora.
 */
@Service
public class ProductionUseCases {

    /** Receita que não é da dona. */
    static final String NO_RECIPE = "Receita não encontrada.";

    /** Máximo de unidades por produção (barra erro de digitação). */
    private static final BigDecimal MAX_QTY = new BigDecimal("100000");

    /** Receitas já precificadas. */
    private final RecipePricing pricing;

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Estoque de produção e histórico. */
    private final ProductionStore store;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /** Serializador do JSON dos ingredientes consumidos. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Cria os casos de uso.
     *
     * @param pricing     receitas precificadas
     * @param ingredients ingredientes
     * @param store       estoque de produção e histórico
     * @param audit       histórico de auditoria
     */
    public ProductionUseCases(RecipePricing pricing, Ingredients ingredients, ProductionStore store,
            AuditTrail audit) {
        this.pricing = pricing;
        this.ingredients = ingredients;
        this.store = store;
        this.audit = audit;
    }

    /**
     * Estoque de produção: uma linha por receita e o resumo.
     *
     * @param owner dona
     * @return {@code recipes} e {@code summary}
     */
    public Map<String, Object> overview(OwnerId owner) {

        // ── 1. Uma linha por receita, com custo, preço, lucro e quantas unidades o estoque de ingredientes cobre.
        Map<Long, Map<String, Object>> byId = ingredientsById(owner);
        Map<Long, BigDecimal> stock = store.recipeStock(owner);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PricedRecipe recipe : pricing.priced(owner)) {
            rows.add(row(recipe, byId, stock.getOrDefault(recipe.id(), BigDecimal.ZERO)));
        }

        // ── 2. Resumo: unidades, custo, receita e lucro do que está pronto.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("units", sum(rows, "qty", null));
        summary.put("cost", sum(rows, "qty", "unit_cost"));
        summary.put("revenue", sum(rows, "qty", "price"));
        summary.put("profit", sum(rows, "qty", "profit_unit"));
        return Map.of("recipes", rows, "summary", summary);
    }

    /**
     * Soma de {@code a} (× {@code b}, se houver) das linhas; valor ausente conta como 0.
     *
     * @param rows linhas
     * @param a    primeira coluna
     * @param b    segunda coluna (nulo = só a primeira)
     * @return a soma
     */
    private static double sum(List<Map<String, Object>> rows, String a, String b) {
        double total = 0;
        for (Map<String, Object> r : rows) {
            double x = r.get(a) instanceof Number n ? n.doubleValue() : 0;
            double y = b == null ? 1 : r.get(b) instanceof Number n ? n.doubleValue() : 0;
            total += x * y;
        }
        return Math.round(total * 100) / 100.0;
    }

    /**
     * A linha de uma receita no estoque de produção. O custo por unidade é o dos ingredientes de uma receita inteira
     * dividido pelo rendimento (a quantidade de um ingrediente por unidade é pequena demais para arredondar sozinha).
     *
     * @param recipe receita precificada
     * @param byId   ingredientes por id
     * @param qty    unidades prontas
     * @return a linha
     */
    private Map<String, Object> row(PricedRecipe recipe, Map<Long, Map<String, Object>> byId, BigDecimal qty) {

        // ── 1. Uma receita inteira: custo por unidade e preço.
        Map<Long, BigDecimal> stock = stockOf(byId);
        BigDecimal yield = new BigDecimal(String.valueOf(recipe.row().get("yield_units")));
        ProductionPlan batch = ProductionPlan.of(recipe.row(), byId, stock, yield);
        boolean hasItems = !batch.lines().isEmpty() && yield.signum() > 0;
        double price = recipe.pricing().rounded();
        Double unitCost = hasItems ? batch.cost().divide(yield, 4, RoundingMode.HALF_UP).doubleValue() : null;
        Double profit = unitCost != null && price > 0 ? Math.round((price - unitCost) * 100) / 100.0 : null;

        // ── 2. A linha da API (nulo onde não há ingrediente ou preço).
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipe_id", recipe.id());
        out.put("name", recipe.name());
        out.put("qty", qty.doubleValue());
        out.put("unit_cost", unitCost);
        out.put("price", price > 0 ? price : null);
        out.put("profit_unit", profit);
        out.put("margin_pct", profit == null ? null : Math.round(profit / price * 1000) / 10.0);
        out.put("can_make", hasItems ? canMake(batch, yield) : null);
        out.put("blocking", hasItems ? blocking(batch, yield) : List.of());
        return out;
    }

    /**
     * Estoque de cada ingrediente, por id.
     *
     * @param byId ingredientes por id
     * @return o estoque
     */
    private static Map<Long, BigDecimal> stockOf(Map<Long, Map<String, Object>> byId) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        byId.forEach((id, ing) -> out.put(id, new BigDecimal(String.valueOf(ing.get("stock_qty")))));
        return out;
    }

    /**
     * Quantas unidades inteiras o estoque de ingredientes cobre (o ingrediente mais escasso manda).
     *
     * @param batch plano de uma receita inteira
     * @param yield unidades que a receita rende
     * @return as unidades
     */
    private static int canMake(ProductionPlan batch, BigDecimal yield) {
        BigDecimal best = null;
        for (Line l : batch.lines()) {
            if (l.needed().signum() <= 0) {
                continue;
            }
            BigDecimal units = l.stock().multiply(yield).divide(l.needed(), 0, RoundingMode.FLOOR);
            best = best == null ? units : best.min(units);
        }
        return best == null ? 0 : best.intValue();
    }

    /**
     * Nomes dos ingredientes que não deixam produzir nem uma unidade.
     *
     * @param batch plano de uma receita inteira
     * @param yield unidades que a receita rende
     * @return os nomes
     */
    private static List<String> blocking(ProductionPlan batch, BigDecimal yield) {
        return batch.lines().stream().filter(l -> l.needed().signum() > 0
                && l.stock().multiply(yield).compareTo(l.needed()) < 0).map(Line::name).toList();
    }

    /**
     * Prévia de uma produção: o que seria descontado, o custo e o que falta (não grava nada).
     *
     * @param owner dona
     * @param body  {@code recipe_id} e {@code qty}
     * @return a prévia
     */
    public Map<String, Object> preview(OwnerId owner, Map<String, Object> body) {
        PricedRecipe recipe = recipe(owner, body);
        BigDecimal qty = qty(body);
        Map<Long, Map<String, Object>> byId = ingredientsById(owner);
        return view(recipe, qty, ProductionPlan.of(recipe.row(), byId, stockOf(byId), qty));
    }

    /**
     * Registra uma produção: desconta os ingredientes, soma as unidades e grava o histórico, tudo ou nada.
     *
     * @param owner dona
     * @param body  {@code recipe_id}, {@code qty} e {@code force} (produzir mesmo com ingrediente faltando)
     * @return a produção registrada
     * @throws ConflictException se faltar ingrediente e {@code force} não vier
     */
    @Transactional
    public Map<String, Object> register(OwnerId owner, Map<String, Object> body) {

        // ── 1. A receita, a quantidade e os estoques de ingrediente travados (o plano usa o valor travado).
        PricedRecipe recipe = recipe(owner, body);
        BigDecimal qty = qty(body);
        Map<Long, Map<String, Object>> byId = ingredientsById(owner);
        ProductionPlan first = ProductionPlan.of(recipe.row(), byId, stockOf(byId), qty);
        if (first.lines().isEmpty()) {
            throw new InvalidInputException("Esta receita não tem ingredientes cadastrados.");
        }
        Map<Long, BigDecimal> locked = store.lockIngredientStock(owner,
                first.lines().stream().map(Line::ingredientId).toList());
        ProductionPlan plan = ProductionPlan.of(recipe.row(), byId, locked, qty);

        // ── 2. Falta ingrediente: só segue se a dona pediu para produzir mesmo assim.
        boolean force = Boolean.TRUE.equals(body.get("force"));
        if (!plan.shortages().isEmpty() && !force) {
            throw new ConflictException("Faltam ingredientes: " + plan.shortages().stream()
                    .map(l -> l.name() + " (faltam " + l.missing().stripTrailingZeros().toPlainString() + " "
                            + l.unit() + ")").collect(Collectors.joining(", ")) + ".");
        }

        // ── 3. Desconta (nunca abaixo de zero), soma as unidades e grava o histórico.
        for (Line l : plan.lines()) {
            store.setIngredientStock(owner, l.ingredientId(), l.packsAfter());
        }
        BigDecimal ready = store.lockRecipeStock(owner, recipe.id());
        store.setRecipeStock(owner, recipe.id(), ready.add(qty));
        Map<String, Object> run = store.insertRun(owner, recipe.id(), recipe.name(), qty, plan.cost(),
                consumedJson(plan), !plan.shortages().isEmpty());
        audit.record(owner, Entry.of("estoque", "produziu", "production_run", run.get("id"), null, run,
                "Produção: " + qty.stripTrailingZeros().toPlainString() + " × " + recipe.name() + " (custo R$ "
                        + plan.cost().toPlainString() + ")"));
        return run;
    }

    /**
     * Histórico de produção.
     *
     * @param owner  dona
     * @param recipe filtro por receita (vazio = todas)
     * @param from   data inicial ISO (vazio = sem limite)
     * @param to     data final ISO (vazio = sem limite)
     * @return as produções, mais novas primeiro
     */
    public List<Map<String, Object>> runs(OwnerId owner, String recipe, String from, String to) {
        return store.runs(owner, NodeInput.positiveId(recipe).orElse(null), day(from), day(to));
    }

    /**
     * Desfaz uma produção: devolve os ingredientes e tira as unidades. Bloqueado se as unidades já saíram do estoque.
     *
     * @param owner dona
     * @param id    produção
     * @return a produção, já desfeita
     * @throws ConflictException se já foi desfeita ou se as unidades já não estão todas no estoque
     */
    @Transactional
    public Map<String, Object> undo(OwnerId owner, long id) {

        // ── 1. A produção existe, é da dona e ainda vale.
        Map<String, Object> run = store.lockRun(owner, id).orElseThrow(() -> new NotFoundException(
                "Produção não encontrada."));
        if (run.get("undone_at") != null) {
            throw new ConflictException("Esta produção já foi desfeita.");
        }

        // ── 2. As unidades produzidas precisam ainda estar no estoque (senão já foram vendidas).
        BigDecimal qty = new BigDecimal(String.valueOf(run.get("qty")));
        if (run.get("recipe_id") == null) {
            throw new ConflictException("A receita desta produção foi excluída, então não dá para desfazer.");
        }
        long recipeId = ((Number) run.get("recipe_id")).longValue();
        BigDecimal ready = store.lockRecipeStock(owner, recipeId);
        if (ready.compareTo(qty) < 0) {
            throw new ConflictException("Só " + ready.stripTrailingZeros().toPlainString() + " das "
                    + qty.stripTrailingZeros().toPlainString() + " unidades ainda estão no estoque de produção; o "
                    + "resto já saiu. Ajuste o estoque de produção se quiser desfazer mesmo assim.");
        }

        // ── 3. Devolve os ingredientes (só o que a produção descontou) e tira as unidades.
        JsonNode consumed = (JsonNode) run.get("consumed");
        List<Long> ids = new ArrayList<>();
        consumed.forEach(c -> ids.add(c.get("ingredient_id").asLong()));
        Map<Long, BigDecimal> locked = store.lockIngredientStock(owner, ids);
        for (JsonNode line : consumed) {
            long ingredient = line.get("ingredient_id").asLong();
            if (locked.containsKey(ingredient)) {
                store.setIngredientStock(owner, ingredient,
                        locked.get(ingredient).add(new BigDecimal(line.get("deducted_packs").asText())));
            }
        }
        store.setRecipeStock(owner, recipeId, ready.subtract(qty));
        Map<String, Object> undone = store.markUndone(id);
        audit.record(owner, Entry.of("estoque", "desfez", "production_run", id, run, undone,
                "Produção desfeita: " + qty.stripTrailingZeros().toPlainString() + " × " + run.get("recipe")));
        return undone;
    }

    /**
     * Corrige à mão as unidades prontas de uma receita (vendas feitas fora do sistema, perdas, contagem).
     *
     * @param owner    dona
     * @param recipeId receita
     * @param body     {@code qty}: a quantidade certa agora
     * @return a linha da receita no estoque de produção
     */
    @Transactional
    public Map<String, Object> adjust(OwnerId owner, long recipeId, Map<String, Object> body) {

        // ── 1. A receita é da dona e a quantidade é válida.
        PricedRecipe recipe = pricing.priced(owner).stream().filter(r -> r.id() == recipeId).findFirst()
                .orElseThrow(() -> new NotFoundException(NO_RECIPE));
        BigDecimal qty = NodeInput.decimal(body.get("qty"), -1).setScale(3, RoundingMode.HALF_UP);
        if (qty.signum() < 0 || qty.compareTo(MAX_QTY) > 0) {
            throw new InvalidInputException("Informe uma quantidade entre 0 e 100000.");
        }

        // ── 2. Grava e registra no histórico.
        BigDecimal was = store.lockRecipeStock(owner, recipeId);
        store.setRecipeStock(owner, recipeId, qty);
        audit.record(owner, Entry.of("estoque", "ajustou", "recipe_stock", recipeId,
                Map.<String, Object>of("qty", was), Map.<String, Object>of("qty", qty),
                "Estoque de produção de " + recipe.name() + ": " + was.stripTrailingZeros().toPlainString()
                        + " para " + qty.stripTrailingZeros().toPlainString()));
        return row(recipe, ingredientsById(owner), qty);
    }

    /**
     * Prévia no formato da API.
     *
     * @param recipe receita
     * @param qty    unidades
     * @param plan   plano
     * @return a prévia
     */
    private static Map<String, Object> view(PricedRecipe recipe, BigDecimal qty, ProductionPlan plan) {

        // ── 1. Cada ingrediente: o que sai, o estoque de antes e de depois e o que falta.
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Line l : plan.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ingredient_id", l.ingredientId());
            m.put("name", l.name());
            m.put("unit", l.unit());
            m.put("needed", l.needed().doubleValue());
            m.put("stock", l.stock().doubleValue());
            m.put("after", l.after().doubleValue());
            m.put("missing", l.missing().doubleValue());
            m.put("cost", l.cost().setScale(2, RoundingMode.HALF_UP).doubleValue());
            lines.add(m);
        }

        // ── 2. Monta a resposta com o custo total e o aviso de falta de ingrediente.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipe_id", recipe.id());
        out.put("recipe", recipe.name());
        out.put("qty", qty.doubleValue());
        out.put("total_cost", plan.cost().doubleValue());
        out.put("lines", lines);
        out.put("short", !plan.shortages().isEmpty());
        return out;
    }

    /**
     * JSON dos ingredientes que a produção descontou.
     *
     * @param plan plano
     * @return o JSON
     */
    private String consumedJson(ProductionPlan plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Line l : plan.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ingredient_id", l.ingredientId());
            m.put("name", l.name());
            m.put("unit", l.unit());
            m.put("needed", l.needed());
            m.put("deducted_packs", l.deductedPacks());
            m.put("cost", l.cost().setScale(2, RoundingMode.HALF_UP));
            out.add(m);
        }
        return json.writeValueAsString(out);
    }

    /**
     * A receita pedida, entre as da dona.
     *
     * @param owner dona
     * @param body  corpo com {@code recipe_id}
     * @return a receita precificada
     */
    private PricedRecipe recipe(OwnerId owner, Map<String, Object> body) {
        long id = NodeInput.positiveId(body.get("recipe_id")).orElseThrow(() -> new InvalidInputException(
                "Escolha a receita."));
        return pricing.priced(owner).stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException(NO_RECIPE));
    }

    /**
     * Unidades a produzir: inteiro de 1 a 100000.
     *
     * @param body corpo com {@code qty}
     * @return a quantidade
     */
    private static BigDecimal qty(Map<String, Object> body) {
        BigDecimal qty = NodeInput.decimal(body.get("qty"), 0);
        if (qty.signum() <= 0 || qty.compareTo(MAX_QTY) > 0 || qty.stripTrailingZeros().scale() > 0) {
            throw new InvalidInputException("Informe quantas unidades foram produzidas (um número inteiro maior que "
                    + "zero).");
        }
        return qty.setScale(3, RoundingMode.UNNECESSARY);
    }

    /**
     * Ingredientes da dona por id.
     *
     * @param owner dona
     * @return as linhas do Node por id
     */
    private Map<Long, Map<String, Object>> ingredientsById(OwnerId owner) {
        return ingredients.list(owner).stream().collect(Collectors.toMap(i -> ((Number) i.get("id")).longValue(),
                Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Dia ISO opcional.
     *
     * @param text texto (vazio = nulo)
     * @return a data
     * @throws InvalidInputException se vier preenchido e inválido
     */
    private static LocalDate day(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new InvalidInputException("Data inválida.");
        }
    }
}
