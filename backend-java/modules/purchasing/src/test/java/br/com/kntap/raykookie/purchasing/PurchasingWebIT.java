package br.com.kntap.raykookie.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.purchasing.application.RefreshLinkedPricesJob;
import br.com.kntap.raykookie.purchasing.application.ports.SupplierSiteClient;
import br.com.kntap.raykookie.purchasing.domain.SafeUrl;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Compras pela rede, com o Postgres real e os sites de fornecedor simulados (sem rede).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class PurchasingWebIT extends PostgresTestBase {

    /** Página de busca que os sites falsos devolvem. */
    static final AtomicReference<String> SEARCH_PAGE = new AtomicReference<>();

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Atualização diária. */
    @Autowired
    RefreshLinkedPricesJob refresh;

    /** Token da dona. */
    String token;

    /**
     * Sites falsos: a raiz se apresenta como loja Tray; a busca devolve a página do momento; o host
     * {@code quebrado.example} sempre falha.
     */
    @TestConfiguration
    static class FakeSites {

        /**
         * Cliente dos sites.
         *
         * @return o cliente falso
         */
        @Bean
        @Primary
        SupplierSiteClient fakeSites() {
            return url -> {
                if (url.contains("quebrado.example")) {
                    throw new SupplierSiteClient.SiteException("O site respondeu 503");
                }
                return url.contains("/loja/busca.php") ? SEARCH_PAGE.get() : "<html data-tray-theme=\"rk\"></html>";
            };
        }

        /**
         * DNS que responde um IP público.
         *
         * @return o resolvedor
         */
        @Bean
        @Primary
        SafeUrl.HostResolver publicDns() {
            return host -> List.of(InetAddress.getByName("203.0.113.7"));
        }
    }

    /**
     * Entra como a dona e põe a página de busca original.
     *
     * @throws IOException se não ler o fixture
     */
    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/tray-search.html")) {
            SEARCH_PAGE.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        token = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login")
                .jsonPath().getString("token");
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification owner(Object body) {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(body);
    }

    /**
     * Requisição sem corpo como a dona.
     *
     * @return a requisição
     */
    private RequestSpecification owner() {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + token);
    }

    /**
     * Um ingrediente da dona.
     *
     * @param name nome
     * @param cost custo de 1000 g
     * @return o id
     */
    private long ingredient(String name, double cost) {
        return jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 'g', 1000, :cost) RETURNING id""")
                .param("name", name).param("cost", cost).query(Long.class).single();
    }

    /**
     * Site Tray: cadastro, busca, produto ligado, comparação e atualização diária.
     */
    @Test
    @DisplayName("site Tray: cadastro reconhece a loja, busca compara por kg, liga o produto e atualiza todo dia")
    void traySite() {

        // ── 1. Cadastro: o endereço vira a origem https e a loja é reconhecida.
        JsonPath site = owner(Map.of("name", "Romafele IT", "kind", "site", "url", "www.romafele.com.br/qualquer"))
                .post("/api/suppliers").then().statusCode(201).extract().jsonPath();
        assertThat(site.getString("url")).isEqualTo("https://www.romafele.com.br");
        assertThat(site.getString("scraper")).isEqualTo("tray");
        long supplier = site.getLong("id");

        // ── 2. Busca: o produto com tamanho no nome ganha preço por grama.
        long ingredient = ingredient("Jazam IT", 40);
        JsonPath found = owner(Map.of("ingredient_id", ingredient, "term", "coloreti")).post("/api/prices/search")
                .then().statusCode(200).extract().jsonPath();
        String path = "results.find { it.supplier_id == " + supplier + " }.products[0]";
        assertThat(found.getDouble(path + ".per_base")).isEqualTo(0.04499);
        assertThat(found.getBoolean(path + ".match_unit")).isTrue();

        // ── 3. Liga o produto; a atualização grava cotação nova quando o preço muda (uma vez).
        owner(Map.of("ingredient_id", ingredient, "supplier_id", supplier, "name", "JAZAM COLORETI 1 KG",
                "url", "https://www.romafele.com.br/doces/jazam-coloreti-1-kg", "price", 44.99, "size", 1000,
                "unit", "g")).post("/api/prices/link").then().statusCode(201);
        SEARCH_PAGE.set(SEARCH_PAGE.get().replace("data-ga4-price=\"44.99\"", "data-ga4-price=\"50.00\""));
        assertThat(refresh.refresh().changed()).isGreaterThanOrEqualTo(1);
        assertThat(refresh.refresh().changed()).isZero();
        assertThat(owner().get("/api/ingredients/" + ingredient + "/prices").jsonPath().getDouble("quotes[0].price"))
                .isEqualTo(50.0);
        refresh.run();
    }

    /**
     * Site com problema: a busca segue e a atualização pula o site.
     */
    @Test
    @DisplayName("site fora do ar: erro na busca e falha contada na atualização")
    void brokenSite() {
        long ingredient = ingredient("Cacau IT", 30);
        long supplier = jdbc.sql("""
                INSERT INTO suppliers (user_id, name, kind, url, scraper)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), 'Quebrado', 'site', 'https://quebrado.example',
                        'tray') RETURNING id""").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO supplier_products (supplier_id, ingredient_id, external_url, name, pack_size, price)
                VALUES (:supplier, :ingredient, 'https://quebrado.example/p', 'Cacau 1kg', 1000, 30)""")
                .param("supplier", supplier).param("ingredient", ingredient).update();
        JsonPath found = owner(Map.of("ingredient_id", ingredient)).post("/api/prices/search").jsonPath();
        assertThat(found.getString("term")).isEqualTo("Cacau IT");
        assertThat(found.getString("errors.find { it.supplier_id == " + supplier + " }.error"))
                .isEqualTo("O site respondeu 503");
        assertThat(refresh.refresh().failed()).isGreaterThanOrEqualTo(1);
        jdbc.sql("DELETE FROM suppliers WHERE id = :id").param("id", supplier).update();
    }

    /**
     * Loja física, cotação manual e "usar este preço" com alerta de margem.
     */
    @Test
    @DisplayName("loja física, cotação manual e use-price que gera alerta")
    void manualQuoteAndUse() {

        // ── 1. Receita que usa o ingrediente (preço sugerido sobe com o ingrediente).
        long ingredient = ingredient("Chocolate IT " + UUID.randomUUID(), 32);
        long recipe = jdbc.sql("""
                INSERT INTO recipes (user_id, name, yield_units, margin_pct)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), 'Brownie IT', 10, 100) RETURNING id""")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 500)")
                .param("r", recipe).param("i", ingredient).update();

        // ── 2. Loja física e cotação de R$ 64/kg.
        long shop = owner(Map.of("name", "Mercado IT", "kind", "loja", "address", "Rua A")).post("/api/suppliers")
                .jsonPath().getLong("id");
        String quote = owner(Map.of("ingredient_id", ingredient, "supplier_id", shop, "price", "64,00", "pack_size",
                "1000")).post("/api/prices/quote").then().statusCode(201).extract().jsonPath().getString("quote_id");

        // ── 3. Usar o preço gera um alerta; unidade diferente é conflito.
        assertThat(owner(Map.of("quote_id", quote)).post("/api/ingredients/" + ingredient + "/use-price").jsonPath()
                .getInt("alerts")).isEqualTo(1);
        jdbc.sql("UPDATE price_quotes SET pack_unit = 'ml' WHERE id = :id").param("id", Long.parseLong(quote)).update();
        assertThat(owner(Map.of("quote_id", quote)).post("/api/ingredients/" + ingredient + "/use-price").statusCode())
                .isEqualTo(409);
        owner(Map.of("name", "Mercado IT 2", "kind", "loja")).patch("/api/suppliers/" + shop).then().statusCode(200);
        assertThat(owner().get("/api/suppliers").jsonPath().getList("id", Long.class)).contains(shop);
        owner().delete("/api/suppliers/" + shop).then().statusCode(200);
    }

    /**
     * Nota: leitura com apelido, lançamento e trava de repetição.
     */
    @Test
    @DisplayName("nota: lê, lança com apelido e recusa a repetição (409)")
    void receipt() {

        // ── 1. Lançamento que soma num ingrediente (e lembra o apelido) e cria outro.
        long sugar = ingredient("Açúcar IT", 4);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("key", key, "store", "Mercado", "date", "2026-02-31", "items", List.of(
                Map.of("action", "add", "ingredient_id", sugar, "add_qty", 1.5, "item_name", "ACUCAR REFINADO IT"),
                Map.of("action", "create", "name", "Fermento IT " + key.substring(0, 5), "unit", "g",
                        "package_size", 100, "package_cost", 3)));
        assertThat(owner(body).post("/api/nota/importar").then().statusCode(201).extract().jsonPath().getInt("added"))
                .isEqualTo(1);
        assertThat(owner(body).post("/api/nota/importar").statusCode()).isEqualTo(409);

        // ── 2. Leitura de uma nota com esse item: o apelido faz casar direto.
        String page = "<table><tr><td>ACUCAR REFINADO IT (Código: 9) Qtde.: 2 UN: KG Vl. Unit.: 4,50 Vl. Total 9,00"
                + "</td></tr></table>" + " ".repeat(200);
        JsonPath read = owner(Map.of("html", page)).post("/api/nota/ler").then().statusCode(200).extract().jsonPath();
        assertThat(read.getLong("items[0].match.ingredient_id")).isEqualTo(sugar);
        assertThat(read.getBoolean("items[0].match.exact")).isTrue();
        assertThat(read.getDouble("items[0].add.add_qty")).isEqualTo(2.0);
        assertThat(read.getString("already_imported")).isNull();
    }
}
