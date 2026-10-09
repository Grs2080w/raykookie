package br.com.kntap.raykookie.contract.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Alertas de margem e preço: nascem da edição do preço de um ingrediente e são lidos e aplicados em
 * {@code /api/price-alerts} (Tarefa 4.4).
 */
class PriceAlertsContractIT extends CatalogContractBase {

    /**
     * Uma receita com preço fixado que usa um ingrediente cujo preço dobra.
     *
     * @param name nome do ingrediente
     * @return id da receita
     */
    private long recipeWhoseCostDoubles(String name) {

        // ── 1. Chocolate a R$ 32/kg numa receita de 10 unidades com preço fixado em R$ 20.
        long chocolate = ingredient(name, "g", 1000, 32);
        long recipe = recipe(Map.of("name", unique("Trufa"), "yield_units", 10, "margin_pct", 100,
                "sale_price", 20, "items", List.of(Map.of("ingredient_id", chocolate, "qty", 500))));

        // ── 2. O chocolate passa para R$ 64/kg: a margem cai 8 pontos.
        asOwner(Map.of("name", name, "unit", "g", "package_size", 1000, "package_cost", 64))
                .patch("/api/ingredients/" + chocolate).then().statusCode(200);
        return recipe;
    }

    /**
     * O alerta aparece na lista com a causa no formato do Node.
     *
     * @param recipe id da receita
     * @return o alerta
     */
    private JsonPath alertOf(long recipe) {
        Response list = asOwner().get("/api/price-alerts");
        assertJson(list, 200, "price-alerts");
        return new JsonPath(list.asString()).setRootPath("find { it.recipe_id == " + recipe + " }");
    }

    /**
     * Preço do ingrediente dobra: alerta de margem, que a dona aplica.
     */
    @Test
    @DisplayName("margem caiu: alerta listado, apply fixa o preço sugerido e marca como lido")
    void marginAlertIsApplied() {

        // ── 1. O alerta nasce da edição do ingrediente.
        String name = unique("Chocolate");
        long recipe = recipeWhoseCostDoubles(name);
        JsonPath alert = alertOf(recipe);
        assertThat(alert.getString("kind")).isEqualTo("margem");
        assertThat(alert.getString("cause")).isEqualTo(name + ": R$ 32,00 → R$ 64,00/kg");
        assertThat(alert.getDouble("after_value")).isLessThan(alert.getDouble("before_value"));

        // ── 2. Aplicar fixa o sugerido na receita e tira o alerta da lista.
        long id = alert.getLong("id");
        Response applied = asOwner().post("/api/price-alerts/" + id + "/apply");
        assertJson(applied, 200, "price-alert-applied");
        assertThat(applied.jsonPath().getDouble("sale_price")).isEqualTo(alert.getDouble("suggested"));
        assertThat(asOwner().get("/api/recipes/" + recipe).jsonPath().getDouble("sale_price"))
                .isEqualTo(alert.getDouble("suggested"));
        assertThat(asOwner().get("/api/price-alerts").jsonPath().getList("id", Long.class)).doesNotContain(id);
    }

    /**
     * Marcar como lido só esconde o alerta.
     */
    @Test
    @DisplayName("POST /{id} marca como lido; apply de alerta inexistente: 404")
    void dismissAndMissing() {

        // ── 1. Marca como lido.
        long recipe = recipeWhoseCostDoubles(unique("Cacau"));
        long id = alertOf(recipe).getLong("id");
        assertJson(asOwner().post("/api/price-alerts/" + id), 200, "ok");
        assertThat(asOwner().get("/api/price-alerts").jsonPath().getList("id", Long.class)).doesNotContain(id);

        // ── 2. Alerta que não existe.
        assertError(asOwner().post("/api/price-alerts/999999999/apply"), 404, "Alerta não encontrado.");
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/price-alerts"), 401, SESSION_EXPIRED);
        assertError(api().post("/api/price-alerts/1"), 401, SESSION_EXPIRED);
        assertError(api().post("/api/price-alerts/1/apply"), 401, SESSION_EXPIRED);
    }
}
