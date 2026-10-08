package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.PriceBook;
import br.com.kntap.raykookie.purchasing.application.ports.Suppliers;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Cotações dos ingredientes: manual, produto de site ligado, comparação por kg e "usar este preço"
 * ({@code backend/lib/suppliers.ts}).
 */
@Service
public class PriceUseCases {

    /** Ingrediente que não é da dona. */
    static final String INGREDIENT_NOT_FOUND = "Ingrediente não encontrado.";

    /** Ingrediente ou fornecedor que não é da dona. */
    private static final String NOT_FOUND = "Ingrediente ou fornecedor não encontrado.";

    /** Cotações e preços. */
    private final PriceBook prices;

    /** Fornecedores. */
    private final Suppliers suppliers;

    /** Catálogo (histórico e alertas de margem). */
    private final CatalogApi catalog;

    /**
     * Cria os casos de uso.
     *
     * @param prices    cotações e preços
     * @param suppliers fornecedores
     * @param catalog   catálogo
     */
    public PriceUseCases(PriceBook prices, Suppliers suppliers, CatalogApi catalog) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.catalog = catalog;
    }

    /**
     * {@code Number(v)} do JavaScript para um campo do corpo.
     *
     * @param value valor do JSON
     * @return o número (NaN se ausente ou inválido)
     */
    static double jsNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value instanceof String text ? NodeInput.jsNumber(text) : Double.NaN;
    }

    /**
     * Ingrediente e fornecedor da dona, ou 404.
     *
     * @param owner      dona
     * @param body       corpo com {@code ingredient_id} e {@code supplier_id}
     * @return o ingrediente
     */
    private PriceBook.Ingredient ownedPair(OwnerId owner, Map<String, Object> body) {
        long ingredientId = NodeInput.positiveId(body.get("ingredient_id")).orElse(0L);
        long supplierId = NodeInput.positiveId(body.get("supplier_id")).orElse(0L);
        PriceBook.Ingredient ingredient = prices.ingredient(owner, ingredientId).orElse(null);
        if (ingredient == null || !suppliers.exists(owner, supplierId)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return ingredient;
    }

    /**
     * Liga um produto do site ao ingrediente e grava a cotação ({@code linkProduct}).
     *
     * @param owner dona
     * @param body  {@code ingredient_id}, {@code supplier_id}, {@code name}, {@code url}, {@code price},
     *              {@code size}, {@code unit}
     * @return {@code {"ok":true,"quote_id":"…"}}
     */
    public Map<String, Object> link(OwnerId owner, Map<String, Object> body) {

        // ── 1. Ingrediente e fornecedor da dona; tamanho, preço e unidade coerentes.
        PriceBook.Ingredient ingredient = ownedPair(owner, body);
        double size = jsNumber(body.get("size"));
        double price = jsNumber(body.get("price"));
        if (!(size > 0)) {
            throw new InvalidInputException("Informe o tamanho da embalagem (por exemplo, 1000 g).");
        }
        if (!(price > 0)) {
            throw new InvalidInputException("Preço inválido.");
        }
        Object unit = body.get("unit");
        if (NodeInput.truthy(unit) && !Objects.equals(unit, ingredient.unit())) {
            throw new InvalidInputException("A embalagem está em " + NodeInput.text(unit) + ", mas o ingrediente é "
                    + "medido em " + ingredient.unit() + ". Informe o tamanho em " + ingredient.unit() + ".");
        }

        // ── 2. Produto ligado e a cotação dele.
        String url = NodeInput.cut(NodeInput.text(body.get("url")), 500);
        if (url.isEmpty()) {
            throw new InvalidInputException("Produto sem endereço.");
        }
        long supplierId = NodeInput.positiveId(body.get("supplier_id")).orElseThrow();
        long product = prices.linkProduct(new PriceBook.LinkedProduct(supplierId, ingredient.id(), url,
                NodeInput.cut(NodeInput.text(body.get("name")), 200), size, ingredient.unit(), price));
        String quote = prices.addQuote(new PriceBook.Quote(owner, ingredient.id(), supplierId, product, price, size,
                ingredient.unit(), "scraper"));
        return Map.of("ok", true, "quote_id", quote);
    }

    /**
     * Cotação manual: loja física ou site sem busca ({@code addQuote}).
     *
     * @param owner dona
     * @param body  {@code ingredient_id}, {@code supplier_id}, {@code price}, {@code pack_size}
     * @return {@code {"ok":true,"quote_id":"…"}}
     */
    public Map<String, Object> quote(OwnerId owner, Map<String, Object> body) {

        // ── 1. Ingrediente e fornecedor da dona, preço e tamanho positivos.
        PriceBook.Ingredient ingredient = ownedPair(owner, body);
        double price = decimalComma(body.get("price"));
        double size = decimalComma(body.get("pack_size"));
        if (!(price > 0) || !(size > 0)) {
            throw new InvalidInputException("Informe o preço e o tamanho da embalagem.");
        }

        // ── 2. Grava a cotação manual.
        long supplierId = NodeInput.positiveId(body.get("supplier_id")).orElseThrow();
        String quote = prices.addQuote(new PriceBook.Quote(owner, ingredient.id(), supplierId, null, price, size,
                ingredient.unit(), "manual"));
        return Map.of("ok", true, "quote_id", quote);
    }

    /**
     * {@code Number(String(v).replace(',', '.'))} do Node.
     *
     * @param value valor do JSON
     * @return o número, ou NaN
     */
    private static double decimalComma(Object value) {
        return value == null ? Double.NaN : NodeInput.jsNumber(NodeInput.text(value).replaceFirst(",", "."));
    }

    /**
     * Última cotação de cada fornecedor, da mais barata (por kg/L/un) para a mais cara, e o histórico
     * ({@code ingredientPrices}).
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @return ingrediente, custo atual por unidade-base, cotações e histórico
     * @throws NotFoundException se o ingrediente não for da dona
     */
    public Map<String, Object> ingredientPrices(OwnerId owner, long ingredient) {

        // ── 1. O ingrediente e as cotações, da mais barata por unidade-base para a mais cara.
        Map<String, Object> row = prices.ingredientRow(owner, ingredient)
                .orElseThrow(() -> new NotFoundException(INGREDIENT_NOT_FOUND));
        List<Map<String, Object>> latest = new ArrayList<>(prices.latestBySupplier(owner, ingredient));
        latest.sort(Comparator.comparingDouble(quote -> ((Number) quote.get("per_base")).doubleValue()));

        // ── 2. Custo atual por unidade-base e o histórico.
        double size = ((Number) row.get("package_size")).doubleValue();
        double cost = ((Number) row.get("package_cost")).doubleValue();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ingredient", row);
        json.put("current_per_base", NodeRows.number(size > 0 ? cost / size : 0));
        json.put("quotes", latest);
        json.put("history", prices.history(owner, ingredient));
        return json;
    }

    /**
     * "Usar este preço": passa o preço da cotação para o ingrediente e dispara os alertas ({@code useQuote}).
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @param body       {@code quote_id}
     * @return {@code {"ok":true,"alerts":n}}
     * @throws NotFoundException se a cotação ou o ingrediente não forem da dona
     * @throws ConflictException se a cotação estiver em outra unidade
     */
    public Map<String, Object> useQuote(OwnerId owner, long ingredient, Map<String, Object> body) {

        // ── 1. A cotação e o ingrediente, na mesma unidade.
        long quoteId = NodeInput.positiveId(body.get("quote_id")).orElse(0L);
        PriceBook.Quote quote = prices.quote(owner, ingredient, quoteId).orElse(null);
        PriceBook.Ingredient current = prices.ingredient(owner, ingredient).orElse(null);
        if (quote == null || current == null) {
            throw new NotFoundException("Cotação não encontrada.");
        }
        if (!quote.unit().equals(current.unit())) {
            throw new ConflictException("Essa cotação está em " + quote.unit() + " e o ingrediente em "
                    + current.unit() + ".");
        }

        // ── 2. Troca o preço e registra a mudança (histórico e alertas).
        prices.setIngredientPrice(ingredient, quote.price(), quote.size());
        int alerts = catalog.priceChanged(new PriceChange(owner, ingredient,
                new PriceChange.PriceState(current.cost(), current.size()),
                new PriceChange.PriceState(quote.price(), quote.size()), "manual", quote.supplierId()));
        return Map.of("ok", true, "alerts", alerts);
    }
}
