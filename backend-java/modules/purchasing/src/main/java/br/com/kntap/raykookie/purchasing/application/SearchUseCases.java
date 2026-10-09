package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.PriceBook;
import br.com.kntap.raykookie.purchasing.application.ports.Suppliers;
import br.com.kntap.raykookie.purchasing.domain.TrayParser.ScrapedProduct;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Busca do ingrediente nos sites dos fornecedores ({@code searchPrices}): um site que falha não derruba a busca.
 */
@Service
public class SearchUseCases {

    /** Produtos por site na resposta. */
    private static final int MAX_PRODUCTS = 30;

    /** Tamanho máximo da mensagem de erro guardada. */
    private static final int MAX_ERROR = 200;

    /** Cotações e ingredientes. */
    private final PriceBook prices;

    /** Fornecedores. */
    private final Suppliers suppliers;

    /** Busca nas lojas Tray. */
    private final TraySearch tray;

    /**
     * Cria os casos de uso.
     *
     * @param prices    cotações e ingredientes
     * @param suppliers fornecedores
     * @param tray      busca nas lojas Tray
     */
    public SearchUseCases(PriceBook prices, Suppliers suppliers, TraySearch tray) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.tray = tray;
    }

    /**
     * Procura o ingrediente em cada site com busca reconhecida.
     *
     * @param owner dona
     * @param body  {@code ingredient_id} e {@code term} (opcional; padrão = nome do ingrediente)
     * @return termo, unidade, resultados por site e erros
     * @throws NotFoundException se o ingrediente não for da dona
     */
    public Map<String, Object> search(OwnerId owner, Map<String, Object> body) {

        // ── 1. Ingrediente e termo.
        long id = NodeInput.positiveId(body.get("ingredient_id")).orElse(0L);
        PriceBook.Ingredient ingredient = prices.ingredient(owner, id)
                .orElseThrow(() -> new NotFoundException(PriceUseCases.INGREDIENT_NOT_FOUND));
        Object term = body.get("term");
        String text = NodeInput.cut(NodeInput.text(NodeInput.truthy(term) ? term : ingredient.name()), 80);

        // ── 2. Cada site; a falha de um vira erro na lista e fica guardada no fornecedor.
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Suppliers.Site site : suppliers.searchableSites(owner)) {
            try {
                List<ScrapedProduct> found = tray.search(site.url(), text);
                suppliers.markOk(site.id());
                results.add(result(site, found, ingredient.unit()));
            } catch (RuntimeException error) {
                String message = NodeInput.cut(error.getMessage() == null ? error.toString() : error.getMessage(),
                        MAX_ERROR);
                suppliers.markError(site.id(), message);
                errors.add(Map.of("supplier_id", site.id(), "supplier", site.name(), "error", message));
            }
        }

        // ── 3. Resposta no formato do Node.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("term", text);
        json.put("unit", ingredient.unit());
        json.put("results", results);
        json.put("errors", errors);
        return json;
    }

    /**
     * Um produto, com o preço por unidade-base quando a embalagem está na unidade do ingrediente.
     *
     * @param product produto
     * @param unit    unidade do ingrediente
     * @return o produto no formato do Node
     */
    private static Map<String, Object> product(ScrapedProduct product, String unit) {

        // ── 1. O que a loja mostra.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", product.name());
        json.put("url", product.url());
        json.put("price", NodeRows.number(product.price()));
        json.put("available", product.available());
        json.put("size", product.size() == null ? null : NodeRows.number(product.size()));
        json.put("unit", product.unit());

        // ── 2. Comparação com o ingrediente.
        boolean sameUnit = unit.equals(product.unit());
        boolean hasSize = product.size() != null && product.size() != 0;
        json.put("match_unit", sameUnit);
        json.put("per_base", hasSize && sameUnit ? NodeRows.number(product.price() / product.size()) : null);
        return json;
    }

    /**
     * Resultado de um site, com o preço por unidade-base quando a embalagem está na unidade do ingrediente.
     *
     * @param site     site
     * @param products produtos
     * @param unit     unidade do ingrediente
     * @return o resultado no formato do Node
     */
    private static Map<String, Object> result(Suppliers.Site site, List<ScrapedProduct> products, String unit) {
        List<Map<String, Object>> list = products.subList(0, Math.min(products.size(), MAX_PRODUCTS)).stream()
                .map(product -> product(product, unit)).toList();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("supplier_id", site.id());
        json.put("supplier", site.name());
        json.put("products", list);
        return json;
    }
}
