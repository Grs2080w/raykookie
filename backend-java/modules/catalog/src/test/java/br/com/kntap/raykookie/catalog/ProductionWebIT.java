package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Estoque de produção pela rede, com o Postgres real: custo e lucro por unidade, prévia, produção que desconta os
 * ingredientes (sem deixar negativo), histórico e desfazer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class ProductionWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

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
     * Estoque atual de um ingrediente.
     *
     * @param id ingrediente
     * @return o estoque
     */
    private double stockOf(long id) {
        return owner().get("/api/ingredients").jsonPath().getDouble("find { it.id == " + id + " }.stock_qty");
    }

    /**
     * Linha da receita no estoque de produção.
     *
     * @param recipe receita
     * @return os campos da linha
     */
    private Map<String, Object> line(long recipe) {
        return owner().get("/api/production/stock").jsonPath().getMap("recipes.find { it.recipe_id == " + recipe + " }");
    }

    /**
     * Do cálculo do custo ao desfazer.
     */
    @Test
    @DisplayName("produção: custo e lucro, prévia, falta bloqueia, forçar não deixa negativo, histórico e desfazer")
    void productionFlow() {

        // ── 1. Receita de 10 unidades: 500 g de A (R$ 0,02/g) e 200 ml de B (R$ 0,01/ml) = R$ 12,00 de ingredientes.
        // O estoque é em embalagens de 1000: A tem 1 (1000 g) e B tem 0,1 (100 ml).
        long a = owner(Map.of("name", "Prod A", "unit", "g", "package_size", 1000, "package_cost", 20,
                "stock_qty", 1)).post("/api/ingredients").then().statusCode(201).extract().jsonPath().getLong("id");
        long b = owner(Map.of("name", "Prod B", "unit", "ml", "package_size", 1000, "package_cost", 10,
                "stock_qty", 0.1)).post("/api/ingredients").then().statusCode(201).extract().jsonPath().getLong("id");
        long recipe = owner(Map.of("name", "Prod Bolo", "yield_units", 10, "sale_price", "12",
                "items", List.of(Map.of("ingredient_id", a, "qty", 500), Map.of("ingredient_id", b, "qty", 200))))
                .post("/api/recipes").then().statusCode(201).extract().jsonPath().getLong("id");
        Map<String, Object> row = line(recipe);
        assertThat(((Number) row.get("unit_cost")).doubleValue()).isCloseTo(1.2, within(0.001));
        assertThat(((Number) row.get("profit_unit")).doubleValue()).isCloseTo(10.8, within(0.001));
        assertThat(((Number) row.get("margin_pct")).doubleValue()).isCloseTo(90.0, within(0.001));
        assertThat(row.get("can_make")).isEqualTo(5);

        // ── 2. Prévia: 20 unidades pedem 400 ml de B e só há 100.
        JsonPath preview = owner(Map.of("recipe_id", recipe, "qty", 20)).post("/api/production/preview")
                .then().statusCode(200).extract().jsonPath();
        assertThat(preview.getBoolean("short")).isTrue();
        assertThat(preview.getDouble("lines.find { it.name == 'Prod B' }.missing")).isEqualTo(300.0);

        // ── 3. Faltando ingrediente e sem "produzir mesmo assim": 409 e nada muda (tudo ou nada).
        owner(Map.of("recipe_id", recipe, "qty", 20)).post("/api/production/runs").then().statusCode(409);
        assertThat(stockOf(a)).isEqualTo(1.0);
        assertThat(stockOf(b)).isEqualTo(0.1);
        assertThat(((Number) line(recipe).get("qty")).doubleValue()).isZero();

        // ── 4. Produção que cabe: desconta os ingredientes e soma as unidades.
        long first = owner(Map.of("recipe_id", recipe, "qty", 5)).post("/api/production/runs")
                .then().statusCode(201).extract().jsonPath().getLong("id");
        assertThat(stockOf(a)).isEqualTo(0.75);
        assertThat(stockOf(b)).isZero();
        assertThat(((Number) line(recipe).get("qty")).doubleValue()).isEqualTo(5.0);

        // ── 5. Forçar com B zerado: B fica em 0 (nunca negativo) e A é descontado.
        long second = owner(Map.of("recipe_id", recipe, "qty", 10, "force", true)).post("/api/production/runs")
                .then().statusCode(201).extract().jsonPath().getLong("id");
        assertThat(stockOf(a)).isEqualTo(0.25);
        assertThat(stockOf(b)).isZero();
        assertThat(owner().get("/api/production/runs?recipe=" + recipe).jsonPath().getList("id")).hasSize(2);

        // ── 6. Desfazer a forçada devolve só o que ela descontou de fato (A, não o B que faltava).
        owner().post("/api/production/runs/" + second + "/undo").then().statusCode(200);
        assertThat(stockOf(a)).isEqualTo(0.75);
        assertThat(stockOf(b)).isZero();
        owner().post("/api/production/runs/" + second + "/undo").then().statusCode(409);

        // ── 7. Unidades que já saíram do estoque bloqueiam o desfazer.
        owner(Map.of("qty", 2)).patch("/api/production/stock/" + recipe).then().statusCode(200);
        owner().post("/api/production/runs/" + first + "/undo").then().statusCode(409);
        owner(Map.of("qty", 5)).patch("/api/production/stock/" + recipe).then().statusCode(200);
        owner().post("/api/production/runs/" + first + "/undo").then().statusCode(200);
        assertThat(stockOf(a)).isEqualTo(1.0);
        assertThat(stockOf(b)).isEqualTo(0.1);
        assertThat(((Number) line(recipe).get("qty")).doubleValue()).isZero();
    }

    /**
     * Entradas inválidas.
     */
    @Test
    @DisplayName("produção: quantidade zero, fracionada ou negativa, e receita sem ingredientes, são recusadas")
    void validation() {
        long empty = owner(Map.of("name", "Prod Vazia", "yield_units", 4, "items", List.of())).post("/api/recipes")
                .then().statusCode(201).extract().jsonPath().getLong("id");
        for (Object qty : List.of(0, -3, 1.5, "abc", 1000000)) {
            owner(Map.of("recipe_id", empty, "qty", qty)).post("/api/production/runs").then().statusCode(400);
        }
        owner(Map.of("recipe_id", empty, "qty", 2)).post("/api/production/runs").then().statusCode(400);
        Map<String, Object> row = line(empty);
        assertThat(row.get("unit_cost")).isNull();
        assertThat(row.get("can_make")).isNull();
        owner(Map.of("qty", -1)).patch("/api/production/stock/" + empty).then().statusCode(400);
        owner(Map.of("recipe_id", 999999, "qty", 1)).post("/api/production/runs").then().statusCode(404);
    }
}
