package br.com.kntap.raykookie.contract.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ingredientes: {@code /api/ingredients} e {@code /api/ingredients/{id}} (Tarefa 4.4).
 */
class IngredientsContractIT extends CatalogContractBase {

    /**
     * Corpo de ingrediente válido, que cada teste altera.
     *
     * @return o corpo
     */
    private static Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Farinha"));
        body.put("unit", "g");
        body.put("package_size", "1.000");
        body.put("package_cost", "7,49");
        body.put("stock_qty", 2);
        body.put("min_stock", "1");
        body.put("supplier", "Atacadão");
        body.put("notes", "tipo 1");
        return body;
    }

    /**
     * Cria e lista: os números chegam como número, lidos no formato brasileiro.
     */
    @Test
    @DisplayName("POST cria (201, números pt-BR) e GET lista")
    void createsAndLists() {

        // ── 1. Cria com "1.000" (milhar) e "7,49" (vírgula decimal).
        Response created = asOwner(body()).post("/api/ingredients");
        assertJson(created, 201, "ingredient");
        assertThat(created.jsonPath().getDouble("package_size")).isEqualTo(1000.0);
        assertThat(created.jsonPath().getDouble("package_cost")).isEqualTo(7.49);
        assertThat(created.jsonPath().getString("supplier")).isEqualTo("Atacadão");

        // ── 2. A lista traz o novo ingrediente.
        Response list = asOwner().get("/api/ingredients");
        assertJson(list, 200, "ingredients");
        assertThat(list.jsonPath().getList("id", Long.class)).contains(created.jsonPath().getLong("id"));
    }

    /**
     * Validações com as mensagens do Node.
     */
    @Test
    @DisplayName("validação: nome, unidade, embalagem, número inválido e negativo")
    void validates() {

        // ── 1. Campos obrigatórios.
        Map<String, Object> noName = body();
        noName.put("name", "  ");
        assertError(asOwner(noName).post("/api/ingredients"), 400, "Informe o nome do ingrediente.");
        Map<String, Object> badUnit = body();
        badUnit.put("unit", "kg");
        assertError(asOwner(badUnit).post("/api/ingredients"), 400, "Escolha a unidade (g, ml ou un).");
        Map<String, Object> noSize = body();
        noSize.put("package_size", 0);
        assertError(asOwner(noSize).post("/api/ingredients"), 400,
                "O tamanho da embalagem precisa ser maior que zero.");

        // ── 2. Números: texto e negativo.
        Map<String, Object> text = body();
        text.put("package_cost", "doze reais");
        assertError(asOwner(text).post("/api/ingredients"), 400, "“doze reais” não é um número válido.");
        Map<String, Object> negative = body();
        negative.put("stock_qty", -1);
        assertError(asOwner(negative).post("/api/ingredients"), 400, "Os valores não podem ser negativos.");
    }

    /**
     * Edição completa e edição rápida só do estoque.
     */
    @Test
    @DisplayName("PATCH completo e PATCH só de stock_qty")
    void updates() {

        // ── 1. Edição completa.
        long id = ingredient(unique("Açúcar"), "g", 1000, 5);
        Map<String, Object> changed = body();
        changed.put("name", "Açúcar refinado");
        Response full = asOwner(changed).patch("/api/ingredients/" + id);
        assertJson(full, 200, "ingredient");
        assertThat(full.jsonPath().getString("name")).isEqualTo("Açúcar refinado");

        // ── 2. Só o estoque: os outros campos ficam.
        Response stock = asOwner(Map.of("stock_qty", "3,5")).patch("/api/ingredients/" + id);
        assertJson(stock, 200, "ingredient");
        assertThat(stock.jsonPath().getDouble("stock_qty")).isEqualTo(3.5);
        assertThat(stock.jsonPath().getString("name")).isEqualTo("Açúcar refinado");
    }

    /**
     * Mudar o preço grava o histórico ({@code price_quotes}, origem "edicao") e a data do preço.
     */
    @Test
    @DisplayName("PATCH que muda o preço grava o histórico")
    void priceChangeIsRecorded() {

        // ── 1. Ingrediente e mudança de preço.
        long id = ingredient(unique("Cacau"), "g", 1000, 30);
        Map<String, Object> changed = body();
        changed.put("package_size", 1000);
        changed.put("package_cost", 36);
        asOwner(changed).patch("/api/ingredients/" + id).then().statusCode(200);

        // ── 2. Uma linha no histórico, com o preço novo.
        Object price = seed.value("SELECT price::text FROM price_quotes WHERE ingredient_id = ? AND source = 'edicao'",
                id);
        assertThat(price).isEqualTo("36.00");
        assertThat(seed.value("SELECT price_updated_at IS NOT NULL FROM ingredients WHERE id = ?", id))
                .isEqualTo(true);
    }

    /**
     * Ingrediente de outra conta: 404 na edição; a exclusão não apaga nada.
     */
    @Test
    @DisplayName("ingrediente de outra conta: PATCH 404, DELETE não apaga")
    void foreignIngredient() {

        // ── 1. Ingrediente de outra conta, direto no banco.
        long other = otherAccount();
        long id = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size) "
                + "VALUES (?, 'Alheio', 'g', 1) RETURNING id", other);

        // ── 2. A dona não edita nem apaga.
        assertError(asOwner(body()).patch("/api/ingredients/" + id), 404, "Ingrediente não encontrado.");
        assertError(asOwner(Map.of("stock_qty", 1)).patch("/api/ingredients/" + id), 404,
                "Ingrediente não encontrado.");
        assertJson(asOwner().delete("/api/ingredients/" + id), 200, "ok");
        assertThat(seed.value("SELECT count(*)::int FROM ingredients WHERE id = ?", id)).isEqualTo(1);
    }

    /**
     * Ingrediente usado em receita não é apagado.
     */
    @Test
    @DisplayName("DELETE de ingrediente em uso: 409")
    void deleteInUse() {

        // ── 1. Ingrediente usado numa receita.
        long id = ingredient(unique("Leite"), "ml", 1000, 6);
        recipe(Map.of("name", unique("Pudim"), "items", List.of(Map.of("ingredient_id", id, "qty", 400))));

        // ── 2. Não apaga; sem uso, apaga.
        assertError(asOwner().delete("/api/ingredients/" + id), 409,
                "Este ingrediente é usado em 1 receita(s). Remova-o das receitas antes de excluir.");
        long free = ingredient(unique("Sal"), "g", 1000, 2);
        assertJson(asOwner().delete("/api/ingredients/" + free), 200, "ok");
        assertThat(seed.value("SELECT count(*)::int FROM ingredients WHERE id = ?", free)).isEqualTo(0);
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/ingredients"), 401, SESSION_EXPIRED);
        assertError(api().contentType("application/json").body("{}").post("/api/ingredients"), 401,
                SESSION_EXPIRED);
        assertError(api().contentType("application/json").body("{}").patch("/api/ingredients/1"), 401,
                SESSION_EXPIRED);
        assertError(api().delete("/api/ingredients/1"), 401, SESSION_EXPIRED);
    }
}
