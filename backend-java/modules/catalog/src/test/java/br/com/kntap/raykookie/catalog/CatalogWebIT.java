package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.identity.api.events.OwnerRegistered;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O catálogo pela rede, com a plataforma de verdade e o Postgres real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class CatalogWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** O catálogo visto pelos outros módulos. */
    @Autowired
    CatalogApi catalog;

    /** Eventos. */
    @Autowired
    ApplicationEventPublisher events;

    /** Transações. */
    @Autowired
    TransactionTemplate transactions;

    /** Token da dona. */
    String token;

    /**
     * Entra como a dona.
     */
    @BeforeEach
    void login() {
        token = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login")
                .jsonPath().getString("token");
    }

    /**
     * Requisição como a dona.
     *
     * @return a requisição
     */
    private RequestSpecification owner() {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + token);
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification owner(Object body) {
        return owner().contentType(ContentType.JSON).body(body);
    }

    /**
     * Id da dona.
     *
     * @return o id
     */
    private OwnerId ownerId() {
        return new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
    }

    /**
     * Ingrediente, receita com preço fixado e alerta quando o preço dobra.
     */
    @Test
    @DisplayName("ingrediente → receita → preço dobra → alerta, push e aplicação do sugerido")
    void ingredientRecipeAndAlert() {

        // ── 1. Ingrediente e receita.
        long chocolate = owner(Map.of("name", "Chocolate IT", "unit", "g", "package_size", 1000, "package_cost", 32))
                .post("/api/ingredients").then().statusCode(201).extract().jsonPath().getLong("id");
        long recipe = owner(Map.of("name", "Trufa IT", "yield_units", 10, "sale_price", "20",
                "items", List.of(Map.of("ingredient_id", chocolate, "qty", 500)))).post("/api/recipes")
                .then().statusCode(201).extract().jsonPath().getLong("id");

        // ── 2. O preço dobra: histórico, alerta de margem e o sugerido aplicado.
        owner(Map.of("name", "Chocolate IT", "unit", "g", "package_size", 1000, "package_cost", 64))
                .patch("/api/ingredients/" + chocolate).then().statusCode(200);
        JsonPath alerts = owner().get("/api/price-alerts").then().statusCode(200).extract().jsonPath();
        assertThat(alerts.getString("find { it.recipe_id == " + recipe + " }.cause"))
                .isEqualTo("Chocolate IT: R$ 32,00 → R$ 64,00/kg");
        long alert = alerts.getLong("find { it.recipe_id == " + recipe + " }.id");
        double suggested = owner().post("/api/price-alerts/" + alert + "/apply").then().statusCode(200).extract()
                .jsonPath().getDouble("sale_price");
        assertThat(owner().get("/api/recipes/" + recipe).jsonPath().getDouble("sale_price")).isEqualTo(suggested);

        // ── 3. Em uso, o ingrediente não sai; a receita sai.
        assertThat(owner().delete("/api/ingredients/" + chocolate).statusCode()).isEqualTo(409);
        owner().delete("/api/recipes/" + recipe).then().statusCode(200);
        owner().delete("/api/ingredients/" + chocolate).then().statusCode(200);
    }

    /**
     * Sem preço fixado, o sugerido que sobe 5% ou mais vira alerta "preco"; dispensado, some.
     */
    @Test
    @DisplayName("sugerido sobe: alerta de preço, dispensado some da lista")
    void priceRiseAlert() {
        long flour = owner(Map.of("name", "Farinha IT", "unit", "g", "package_size", 1000, "package_cost", 10))
                .post("/api/ingredients").jsonPath().getLong("id");
        long recipe = owner(Map.of("name", "Pão IT", "yield_units", 1, "items",
                List.of(Map.of("ingredient_id", flour, "qty", 1000)))).post("/api/recipes").jsonPath().getLong("id");
        owner(Map.of("stock_qty", 3)).patch("/api/ingredients/" + flour).then().statusCode(200);
        owner(Map.of("name", "Farinha IT", "unit", "g", "package_size", 1000, "package_cost", 20))
                .patch("/api/ingredients/" + flour).then().statusCode(200);
        JsonPath alerts = owner().get("/api/price-alerts").jsonPath();
        assertThat(alerts.getString("find { it.recipe_id == " + recipe + " }.kind")).isEqualTo("preco");
        long alert = alerts.getLong("find { it.recipe_id == " + recipe + " }.id");
        owner().post("/api/price-alerts/" + alert).then().statusCode(200);
        assertThat(owner().get("/api/price-alerts").jsonPath().getList("id", Long.class)).doesNotContain(alert);
        assertThat(owner().post("/api/price-alerts/" + alert + "/apply").statusCode()).isEqualTo(200);
    }

    /**
     * Lista precificada, editor de cardápio e a visão dos outros módulos.
     */
    @Test
    @DisplayName("lista precificada, editor de cardápio e CatalogApi")
    void pricedListAndMenuEditor() {

        // ── 1. Receita com um ingrediente, depois editada.
        long sugar = owner(Map.of("name", "Açúcar IT", "unit", "g", "package_size", "1.000", "package_cost", "5,00"))
                .post("/api/ingredients").jsonPath().getLong("id");
        Map<String, Object> body = Map.of("name", "Bolo IT", "yield_units", 8, "prep_hours", 1, "items",
                List.of(Map.of("ingredient_id", sugar, "qty", 300)));
        long recipe = owner(body).post("/api/recipes").jsonPath().getLong("id");
        owner(body).put("/api/recipes/" + recipe).then().statusCode(200);

        // ── 2. Lista com o cálculo e editor com os selos.
        Response list = owner().get("/api/recipes");
        assertThat(list.jsonPath().getDouble("find { it.id == " + recipe + " }.pricing.ingredients")).isEqualTo(1.5);
        owner(Map.of("tags", List.of("vegano"), "show_on_menu", false)).patch("/api/recipes/" + recipe + "/cardapio")
                .then().statusCode(200);
        JsonPath editor = owner().get("/api/recipes/cardapio").jsonPath();
        assertThat(editor.getBoolean("find { it.id == " + recipe + " }.priced")).isTrue();
        assertThat(owner(Map.of()).patch("/api/recipes/" + recipe + "/cardapio").statusCode()).isEqualTo(400);

        // ── 3. A mesma receita vista pelos outros módulos.
        PricedRecipe priced = catalog.pricedRecipes(ownerId()).stream().filter(r -> r.id() == recipe).findFirst()
                .orElseThrow();
        assertThat(priced.name()).isEqualTo("Bolo IT");
        assertThat(priced.showOnMenu()).isFalse();
        assertThat(priced.tags()).containsExactly("vegano");
        assertThat(priced.photoVersion()).isNull();
        assertThat(priced.categoryId()).isNull();
        assertThat(priced.unitWeightG()).isZero();
        assertThat(priced.json()).containsKey("pricing");
        assertThat(catalog.ingredients(ownerId())).isNotEmpty();
    }

    /**
     * Foto: envio, entrega com cache, exclusão e recusas.
     */
    @Test
    @DisplayName("foto: PNG guardado e servido; texto 415; vazio 400; apagada some")
    void photos() {
        long recipe = owner(Map.of("name", "Foto IT")).post("/api/recipes").jsonPath().getLong("id");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H'};
        owner().contentType("image/png").body(png).put("/api/recipes/" + recipe + "/photo").then().statusCode(200);
        Response mine = owner().get("/api/fotos/" + recipe);
        assertThat(mine.getHeader("Cache-Control")).isEqualTo("private, max-age=60");
        assertThat(mine.asByteArray()).isEqualTo(png);
        Response open = RestAssured.given().port(port).queryParam("v", "1").get("/api/fotos/" + recipe);
        assertThat(open.getHeader("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(owner().contentType("image/png").body("texto".getBytes()).put("/api/recipes/" + recipe + "/photo")
                .statusCode()).isEqualTo(415);
        assertThat(owner().contentType("image/png").put("/api/recipes/" + recipe + "/photo").statusCode())
                .isEqualTo(400);
        assertThat(owner().contentType("image/png").body(png).put("/api/recipes/999999/photo").statusCode())
                .isEqualTo(404);
        owner().delete("/api/recipes/" + recipe + "/photo").then().statusCode(200);
        assertThat(RestAssured.given().port(port).get("/api/fotos/" + recipe).statusCode()).isEqualTo(404);
        assertThat(RestAssured.given().port(port).get("/api/fotos/abc").statusCode()).isEqualTo(404);
    }

    /**
     * Categorias, gastos fixos e ajustes.
     */
    @Test
    @DisplayName("categorias, gastos fixos e ajustes pela rede")
    void setup() {

        // ── 1. Categoria criada, filtrada, editada e apagada.
        long category = owner(Map.of("kind", "expense", "name", "Luz IT")).post("/api/categories").then()
                .statusCode(201).extract().jsonPath().getLong("id");
        assertThat(owner().queryParam("kind", "expense").get("/api/categories").jsonPath().getList("kind"))
                .containsOnly("expense");
        assertThat(owner().get("/api/categories").jsonPath().getList("kind")).contains("expense");
        owner(Map.of("name", "Energia IT")).patch("/api/categories/" + category).then().statusCode(200);
        assertThat(owner(Map.of("name", "X")).patch("/api/categories/999999").statusCode()).isEqualTo(404);

        // ── 2. Gasto com a categoria; a soma entra no cálculo.
        long cost = owner(Map.of("name", "Conta IT", "amount", "100", "category_id", category))
                .post("/api/fixed-costs").then().statusCode(201).extract().jsonPath().getLong("id");
        owner(Map.of("name", "Conta IT", "amount", 150)).patch("/api/fixed-costs/" + cost).then().statusCode(200);
        assertThat(owner(Map.of("name", "X")).patch("/api/fixed-costs/999999").statusCode()).isEqualTo(404);
        assertThat(owner().get("/api/fixed-costs").jsonPath().getList("id", Long.class)).contains(cost);
        owner().delete("/api/fixed-costs/" + cost).then().statusCode(200);
        owner().delete("/api/categories/" + category).then().statusCode(200);

        // ── 3. Ajustes.
        assertThat(owner(Map.of("hourly_rate", "20", "monthly_units", 200)).put("/api/settings").jsonPath()
                .getDouble("monthly_units")).isEqualTo(200.0);
        assertThat(owner().get("/api/settings").jsonPath().getDouble("hourly_rate")).isEqualTo(20.0);
        owner(Map.of("monthly_units", 100, "min_margin_pct", 30)).put("/api/settings").then().statusCode(200);
    }

    /**
     * Conta nova ganha ajustes e as categorias padrão; mudança de preço de ingrediente alheio não faz nada.
     */
    @Test
    @DisplayName("OwnerRegistered cria os padrões; priceChanged de ingrediente alheio devolve 0")
    void defaultsAndForeignPrice() {

        // ── 1. Conta nova.
        long id = jdbc.sql("INSERT INTO users (username, password_hash) VALUES ('nova-it', 'x') RETURNING id")
                .query(Long.class).single();
        transactions.executeWithoutResult(status -> events.publishEvent(new OwnerRegistered(new OwnerId(id),
                "nova-it")));
        assertThat(catalog.categories(new OwnerId(id), null)).hasSize(36);
        assertThat(catalog.categories(new OwnerId(id), "recipe")).hasSize(12);
        assertThat(jdbc.sql("SELECT count(*) FROM settings WHERE user_id = :id").param("id", id).query(Long.class)
                .single()).isEqualTo(1L);

        // ── 2. Ingrediente que não é da dona (e mudança nula).
        PriceChange.PriceState state = new PriceChange.PriceState(1, 1);
        assertThat(catalog.priceChanged(new PriceChange(ownerId(), 999_999, state,
                new PriceChange.PriceState(2, 1), "manual", null))).isZero();
        assertThat(catalog.priceChanged(new PriceChange(ownerId(), 1, state, state, "manual", null))).isZero();
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertThat(RestAssured.given().port(port).get("/api/ingredients").statusCode()).isEqualTo(401);
        assertThat(RestAssured.given().port(port).get("/api/recipes").statusCode()).isEqualTo(401);
        assertThat(RestAssured.given().port(port).get("/api/settings").statusCode()).isEqualTo(401);
    }
}
