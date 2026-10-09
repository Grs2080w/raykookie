package br.com.kntap.raykookie.contract.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fornecedores, cotações e preços dos ingredientes (Tarefa 4.5). Nenhum teste depende de rede: os fornecedores
 * "site" de verdade buscariam páginas externas, então só os caminhos que param antes disso são exercitados.
 */
class SuppliersAndPricesContractIT extends ContractTestBase {

    /** Mensagem de rota da dona sem sessão. */
    private static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /**
     * Abre a sessão da dona.
     */
    @BeforeEach
    void openSession() {
        owner = seed.owner(target.shopOwner());
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification asOwner(Object body) {
        return api().header("Authorization", owner.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Requisição sem corpo como a dona.
     *
     * @return a requisição
     */
    private RequestSpecification asOwner() {
        return api().header("Authorization", owner.bearer());
    }

    /**
     * Um nome que nenhum outro teste usa.
     *
     * @param prefix começo
     * @return o nome
     */
    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Cria uma loja física e devolve o id.
     *
     * @param name nome
     * @return o id
     */
    private long shop(String name) {
        return asOwner(Map.of("name", name, "kind", "loja", "address", "Rua A, 10")).post("/api/suppliers")
                .then().statusCode(201).extract().jsonPath().getLong("id");
    }

    /**
     * Cria um ingrediente direto no banco.
     *
     * @param cost custo da embalagem de 1000 g
     * @return o id
     */
    private long ingredient(int cost) {
        return seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost) "
                + "VALUES (?, ?, 'g', 1000, ?) RETURNING id", owner.id(), unique("Chocolate"), cost);
    }

    /**
     * Fornecedores: cria, lista, edita, valida e apaga.
     */
    @Test
    @DisplayName("fornecedores: loja física criada, listada, editada e apagada; validações do site")
    void suppliers() {

        // ── 1. Loja física: sem site nem busca.
        Response created = asOwner(Map.of("name", unique("Mercado"), "kind", "loja", "address", "Rua B"))
                .post("/api/suppliers");
        assertJson(created, 201, "supplier");
        assertThat(created.jsonPath().getString("url")).isEmpty();
        assertThat(created.jsonPath().getString("scraper")).isNull();
        long id = created.jsonPath().getLong("id");
        assertJson(asOwner().get("/api/suppliers"), 200, "suppliers");

        // ── 2. Edita; nome curto e site inseguro são recusados.
        Response edited = asOwner(Map.of("name", "Mercado Novo", "kind", "loja")).patch("/api/suppliers/" + id);
        assertJson(edited, 200, "supplier");
        assertError(asOwner(Map.of("name", "A", "kind", "loja")).post("/api/suppliers"), 400,
                "Dê um nome para o fornecedor.");
        assertError(asOwner(Map.of("name", "Site", "kind", "site", "url", "http://loja.com.br"))
                .post("/api/suppliers"), 400, "Use o endereço do site com https.");
        assertError(asOwner(Map.of("name", "Site", "kind", "site", "url", "https://localhost:10005"))
                .post("/api/suppliers"), 400, "Use o endereço de um site público.");
        assertError(asOwner(Map.of("name", "Site", "kind", "site", "url", "https://10.0.0.5/"))
                .post("/api/suppliers"), 400, "Use o endereço de um site público.");
        assertError(asOwner(Map.of("name", "Mercado", "kind", "loja")).patch("/api/suppliers/999999999"), 404,
                "Fornecedor não encontrado.");

        // ── 3. Apaga.
        assertJson(asOwner().delete("/api/suppliers/" + id), 200, "ok");
        assertThat(seed.value("SELECT count(*)::int FROM suppliers WHERE id = ?", id)).isEqualTo(0);
    }

    /**
     * Cotação manual, produto ligado e a comparação por kg.
     */
    @Test
    @DisplayName("cotação manual e produto ligado; GET prices ordena pelo mais barato por kg")
    void quotesAndComparison() {

        // ── 1. Duas cotações: a loja (R$ 29,90/kg) e um produto ligado (R$ 38/kg).
        long ingredient = ingredient(32);
        long cheap = shop(unique("Mercado do Zé"));
        long linked = shop(unique("Atacado"));
        assertJson(asOwner(Map.of("ingredient_id", ingredient, "supplier_id", cheap, "price", "29,90",
                "pack_size", "1000")).post("/api/prices/quote"), 201, "quote-created");
        assertJson(asOwner(Map.of("ingredient_id", ingredient, "supplier_id", linked, "name", "Chocolate 1kg",
                "url", "https://r/choc-1kg", "price", 38, "size", 1000, "unit", "g")).post("/api/prices/link"), 201,
                "quote-created");

        // ── 2. Mais barata primeiro, com o histórico.
        Response prices = asOwner().get("/api/ingredients/" + ingredient + "/prices");
        assertJson(prices, 200, "ingredient-prices");
        JsonPath json = prices.jsonPath();
        assertThat(json.getList("quotes.supplier_id", Long.class)).containsExactly(cheap, linked);
        assertThat(json.getDouble("quotes[0].price")).isEqualTo(29.9);
        assertThat(json.getString("quotes[1].url")).isEqualTo("https://r/choc-1kg");
        assertThat(json.getDouble("current_per_base")).isEqualTo(0.032);
        assertThat(json.getList("history")).hasSize(2);
    }

    /**
     * Validações das cotações.
     */
    @Test
    @DisplayName("cotações: ingrediente/fornecedor alheio 404, tamanho, preço e unidade inválidos 400")
    void quoteValidation() {
        long ingredient = ingredient(10);
        long supplier = shop(unique("Loja"));
        Map<String, Object> link = new HashMap<>(Map.of("ingredient_id", ingredient, "supplier_id", supplier,
                "name", "x", "url", "https://r/x", "price", 38, "size", 1000, "unit", "ml"));
        assertError(asOwner(link).post("/api/prices/link"), 400, "A embalagem está em ml, mas o ingrediente é "
                + "medido em g. Informe o tamanho em g.");
        link.put("unit", "g");
        link.put("size", 0);
        assertError(asOwner(link).post("/api/prices/link"), 400,
                "Informe o tamanho da embalagem (por exemplo, 1000 g).");
        link.put("supplier_id", 999_999_999);
        assertError(asOwner(link).post("/api/prices/link"), 404, "Ingrediente ou fornecedor não encontrado.");
        assertError(asOwner(Map.of("ingredient_id", ingredient, "supplier_id", supplier, "price", "0",
                "pack_size", "1")).post("/api/prices/quote"), 400, "Informe o preço e o tamanho da embalagem.");
        assertError(asOwner().get("/api/ingredients/999999999/prices"), 404, "Ingrediente não encontrado.");
    }

    /**
     * "Usar este preço" troca o preço do ingrediente e grava o histórico.
     */
    @Test
    @DisplayName("use-price: passa a cotação para o ingrediente; cotação alheia 404")
    void usePrice() {

        // ── 1. Cotação de R$ 55/kg.
        long ingredient = ingredient(32);
        long supplier = shop(unique("Mercado"));
        String quote = asOwner(Map.of("ingredient_id", ingredient, "supplier_id", supplier, "price", 55,
                "pack_size", 1000)).post("/api/prices/quote").jsonPath().getString("quote_id");

        // ── 2. Usa: o ingrediente passa a custar R$ 55.
        Response used = asOwner(Map.of("quote_id", quote)).post("/api/ingredients/" + ingredient + "/use-price");
        assertJson(used, 200, "use-price");
        assertThat(seed.value("SELECT package_cost::text FROM ingredients WHERE id = ?", ingredient))
                .isEqualTo("55.00");
        assertError(asOwner(Map.of("quote_id", 999_999_999)).post("/api/ingredients/" + ingredient + "/use-price"),
                404, "Cotação não encontrada.");
    }

    /**
     * Busca: site com endereço inseguro vira erro na lista, sem derrubar a busca.
     */
    @Test
    @DisplayName("busca: site com endereço interno vira erro na lista e grava last_error")
    void searchWithBrokenSite() {

        // ── 1. Um site "tray" apontando para um IP (cadastrado direto no banco).
        long ingredient = ingredient(10);
        long site = seed.insert("INSERT INTO suppliers (user_id, name, kind, url, scraper) "
                + "VALUES (?, ?, 'site', 'https://127.0.0.1', 'tray') RETURNING id", owner.id(), unique("Site"));

        // ── 2. A busca responde, com o erro do site.
        Response search = asOwner(Map.of("ingredient_id", ingredient, "term", "  chocolate  "))
                .post("/api/prices/search");
        assertJson(search, 200, "price-search");
        JsonPath json = search.jsonPath();
        assertThat(json.getString("term")).isEqualTo("chocolate");
        assertThat(json.getString("unit")).isEqualTo("g");
        assertThat(json.getString("errors.find { it.supplier_id == " + site + " }.error"))
                .isEqualTo("Use o endereço de um site público.");
        assertThat(seed.value("SELECT last_error FROM suppliers WHERE id = ?", site))
                .isEqualTo("Use o endereço de um site público.");
        seed.exec("DELETE FROM suppliers WHERE id = ?", site);
        assertError(asOwner(Map.of("ingredient_id", 999_999_999)).post("/api/prices/search"), 404,
                "Ingrediente não encontrado.");
    }

    /**
     * Site com porta fora da 443 é recusado (no Node passava).
     */
    @Test
    @ChangedFromNode("Tarefa 4.5: SafeUrl só na porta 443")
    @DisplayName("site com porta diferente de 443: 400")
    void nonStandardPort() {
        assertError(asOwner(Map.of("name", "Site", "kind", "site", "url", "https://loja.com.br:8443"))
                .post("/api/suppliers"), 400, "Use o endereço de um site público.");
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/suppliers"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/prices/quote"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/prices/link"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/prices/search"), 401, SESSION_EXPIRED);
        assertError(api().get("/api/ingredients/1/prices"), 401, SESSION_EXPIRED);
        assertError(api().delete("/api/suppliers/1"), 401, SESSION_EXPIRED);
    }
}
