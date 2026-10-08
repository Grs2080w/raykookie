package br.com.kntap.raykookie.contract.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Receitas: {@code /api/recipes}, {@code /api/recipes/{id}} e o editor de cardápio (Tarefa 4.4).
 */
class RecipesContractIT extends CatalogContractBase {

    /**
     * Corpo de uma receita com dois ingredientes.
     *
     * @param first  primeiro ingrediente
     * @param second segundo ingrediente
     * @return o corpo
     */
    private static Map<String, Object> body(long first, long second) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Bolo de cenoura"));
        body.put("yield_units", "10");
        body.put("margin_pct", 100);
        body.put("prep_hours", "1,5");
        body.put("notes", "forno 180");
        body.put("unit_weight_g", 120);
        body.put("items", List.of(Map.of("ingredient_id", first, "qty", 200), Map.of("ingredient_id", second,
                "qty", "500")));
        return body;
    }

    /**
     * Cria, lê e lista precificada.
     */
    @Test
    @DisplayName("POST cria (201), GET /{id} lê e GET lista com o preço calculado")
    void createsReadsAndPrices() {

        // ── 1. Dois ingredientes (R$ 0,025/g e R$ 0,0359/g) e a receita.
        long flour = ingredient(unique("Farinha"), "g", 200, 5);
        long sugar = ingredient(unique("Açúcar"), "g", 1000, "35,90");
        Response created = asOwner(body(flour, sugar)).post("/api/recipes");
        assertJson(created, 201, "recipe-saved");
        long id = created.jsonPath().getLong("id");

        // ── 2. Leitura com os itens.
        Response one = asOwner().get("/api/recipes/" + id);
        assertJson(one, 200, "recipe");
        assertThat(one.jsonPath().getList("items.ingredient_id", Long.class)).containsExactly(flour, sugar);
        assertThat(one.jsonPath().getString("photo_v")).isNull();

        // ── 3. Lista com preço: ingredientes = 200 × 0,025 + 500 × 0,0359; arredondado em R$ 0,50.
        Response list = asOwner().get("/api/recipes");
        assertJson(list, 200, "priced-recipes");
        JsonPath pricing = new JsonPath(list.asString()).setRootPath("find { it.id == " + id + " }.pricing");
        assertThat(pricing.getDouble("ingredients")).isCloseTo(22.95, within(1e-9));
        double rounded = pricing.getDouble("rounded");
        assertThat(rounded * 2).isEqualTo(Math.rint(rounded * 2));
        assertThat(rounded).isGreaterThanOrEqualTo(pricing.getDouble("suggested") - 1e-9);
    }

    /**
     * Validações com as mensagens do Node.
     */
    @Test
    @DisplayName("validação: nome, rendimento e ingrediente de outra conta")
    void validates() {

        // ── 1. Nome e rendimento.
        long flour = ingredient(unique("Farinha"), "g", 1000, 5);
        Map<String, Object> noName = body(flour, flour);
        noName.put("name", "");
        assertError(asOwner(noName).post("/api/recipes"), 400, "Dê um nome para a receita.");
        Map<String, Object> noYield = body(flour, flour);
        noYield.put("yield_units", 0);
        assertError(asOwner(noYield).post("/api/recipes"), 400, "O rendimento precisa ser maior que zero.");

        // ── 2. Ingrediente que não é da dona.
        long foreign = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size) "
                + "VALUES (?, 'Alheio', 'g', 1) RETURNING id", otherAccount());
        assertError(asOwner(body(flour, foreign)).post("/api/recipes"), 400,
                "Algum ingrediente não foi encontrado no seu estoque.");
    }

    /**
     * PUT troca os itens; receita de outra conta: 404.
     */
    @Test
    @DisplayName("PUT atualiza e troca os itens; receita de outra conta: 404")
    void updates() {

        // ── 1. Receita com dois itens, atualizada para um item com preço fixado.
        long flour = ingredient(unique("Farinha"), "g", 1000, 5);
        long sugar = ingredient(unique("Açúcar"), "g", 1000, 4);
        long id = recipe(body(flour, sugar));
        Map<String, Object> changed = body(flour, sugar);
        changed.put("items", List.of(Map.of("ingredient_id", sugar, "qty", 100)));
        changed.put("sale_price", "12,50");
        Response updated = asOwner(changed).put("/api/recipes/" + id);
        assertJson(updated, 200, "recipe-saved");
        assertThat(updated.jsonPath().getDouble("sale_price")).isEqualTo(12.5);
        assertThat(asOwner().get("/api/recipes/" + id).jsonPath().getList("items.ingredient_id", Long.class))
                .containsExactly(sugar);

        // ── 2. Receita alheia: 404 no PUT e no GET.
        long foreign = seed.insert("INSERT INTO recipes (user_id, name) VALUES (?, 'Alheia') RETURNING id",
                otherAccount());
        assertError(asOwner(body(flour, sugar)).put("/api/recipes/" + foreign), 404, "Receita não encontrada.");
        assertError(asOwner().get("/api/recipes/" + foreign), 404, "Receita não encontrada.");
    }

    /**
     * Editor de cardápio: lista enxuta e troca de visibilidade/selos.
     */
    @Test
    @DisplayName("editor de cardápio: GET lista, PATCH limpa os selos e exige algo para mudar")
    void menuEditor() {

        // ── 1. Receita sem preço nem itens aparece como "não precificada".
        long id = recipe(Map.of("name", unique("Brigadeiro")));
        Response list = asOwner().get("/api/recipes/cardapio");
        assertJson(list, 200, "menu-editor");
        JsonPath item = new JsonPath(list.asString()).setRootPath("find { it.id == " + id + " }");
        assertThat(item.getBoolean("priced")).isFalse();
        assertThat(item.getBoolean("has_photo")).isFalse();

        // ── 2. Selos inválidos e repetidos saem; visibilidade muda.
        Response flags = asOwner(Map.of("show_on_menu", false, "tags", List.of("vegano", "inventado", "vegano")))
                .patch("/api/recipes/" + id + "/cardapio");
        assertJson(flags, 200, "menu-flags");
        assertThat(flags.jsonPath().getList("tags", String.class)).containsExactly("vegano");
        assertThat(flags.jsonPath().getBoolean("show_on_menu")).isFalse();

        // ── 3. Nada a mudar: 400; receita inexistente: 404.
        assertError(asOwner(Map.of("outro", 1)).patch("/api/recipes/" + id + "/cardapio"), 400,
                "Nada para atualizar.");
        assertError(asOwner(Map.of("show_on_menu", true)).patch("/api/recipes/999999999/cardapio"), 404,
                "Receita não encontrada.");
    }

    /**
     * Exclusão.
     */
    @Test
    @DisplayName("DELETE apaga a receita")
    void deletes() {
        long id = recipe(Map.of("name", unique("Torta")));
        assertJson(asOwner().delete("/api/recipes/" + id), 200, "ok");
        assertError(asOwner().get("/api/recipes/" + id), 404, "Receita não encontrada.");
    }

    /**
     * Sem sessão: 401 em todas as rotas de receita.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/recipes"), 401, SESSION_EXPIRED);
        assertError(api().get("/api/recipes/1"), 401, SESSION_EXPIRED);
        assertError(api().get("/api/recipes/cardapio"), 401, SESSION_EXPIRED);
        assertError(api().contentType("application/json").body("{}").patch("/api/recipes/1/cardapio"), 401,
                SESSION_EXPIRED);
        assertError(api().delete("/api/recipes/1"), 401, SESSION_EXPIRED);
    }
}
