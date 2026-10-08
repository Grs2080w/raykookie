package br.com.kntap.raykookie.contract.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Categorias, gastos fixos e ajustes: {@code /api/categories}, {@code /api/fixed-costs} e
 * {@code /api/settings} (Tarefa 4.4).
 */
class CategoriesCostsSettingsContractIT extends CatalogContractBase {

    /**
     * Categorias: cria, valida, filtra, edita e apaga.
     */
    @Test
    @DisplayName("categorias: POST 201, filtro por tipo com usos, PATCH, nome repetido 409 e DELETE")
    void categories() {

        // ── 1. Cria; cor fora do padrão e ícone vazio viram os padrões.
        String name = unique("Bolos finos");
        Response created = asOwner(Map.of("kind", "recipe", "name", name, "icon", "", "color", "rosa"))
                .post("/api/categories");
        assertJson(created, 201, "category");
        assertThat(created.jsonPath().getString("icon")).isEqualTo("tag");
        assertThat(created.jsonPath().getString("color")).isEqualTo("#e0357a");
        long id = created.jsonPath().getLong("id");

        // ── 2. O filtro por tipo traz só aquele tipo, com a contagem de usos.
        Response list = asOwner().queryParam("kind", "recipe").get("/api/categories");
        assertJson(list, 200, "categories");
        assertThat(list.jsonPath().getList("kind", String.class)).containsOnly("recipe");
        assertThat(list.jsonPath().getList("id", Long.class)).contains(id);

        // ── 3. Edita; nome repetido no mesmo tipo: 409.
        Response renamed = asOwner(Map.of("name", name + " II", "icon", "cake", "color", "#AABBCC"))
                .patch("/api/categories/" + id);
        assertJson(renamed, 200, "category");
        assertThat(renamed.jsonPath().getString("color")).isEqualTo("#AABBCC");
        asOwner(Map.of("kind", "recipe", "name", name)).post("/api/categories").then().statusCode(201);
        assertError(asOwner(Map.of("kind", "recipe", "name", name)).post("/api/categories"), 409,
                "Já existe um registro com esse nome.");

        // ── 4. Apaga.
        assertJson(asOwner().delete("/api/categories/" + id), 200, "ok");
        assertThat(seed.value("SELECT count(*)::int FROM categories WHERE id = ?", id)).isEqualTo(0);
    }

    /**
     * Validações das categorias e categoria de outra conta.
     */
    @Test
    @DisplayName("categorias: tipo inválido, sem nome e de outra conta (404)")
    void categoryValidation() {
        assertError(asOwner(Map.of("kind", "outro", "name", "X")).post("/api/categories"), 400,
                "Tipo de categoria inválido.");
        assertError(asOwner(Map.of("kind", "expense", "name", " ")).post("/api/categories"), 400,
                "Dê um nome para a categoria.");
        long foreign = seed.insert("INSERT INTO categories (user_id, kind, name) VALUES (?, 'recipe', 'Alheia') "
                + "RETURNING id", otherAccount());
        assertError(asOwner(Map.of("name", "Minha")).patch("/api/categories/" + foreign), 404,
                "Categoria não encontrada.");
        assertError(asOwner(Map.of("name", "")).patch("/api/categories/" + foreign), 400,
                "Dê um nome para a categoria.");
    }

    /**
     * Gastos fixos: cria, lista, edita, valida e apaga.
     */
    @Test
    @DisplayName("gastos fixos: POST 201, GET, PATCH, outra conta 404 e DELETE")
    void fixedCosts() {

        // ── 1. Cria um gasto variável e lista.
        Response created = asOwner(Map.of("name", unique("Gás"), "kind", "variable", "amount", "120,50"))
                .post("/api/fixed-costs");
        assertJson(created, 201, "fixed-cost");
        assertThat(created.jsonPath().getDouble("amount")).isEqualTo(120.5);
        long id = created.jsonPath().getLong("id");
        assertJson(asOwner().get("/api/fixed-costs"), 200, "fixed-costs");

        // ── 2. Tipo desconhecido vira "fixed"; sem nome é recusado.
        Response edited = asOwner(Map.of("name", "Gás de cozinha", "kind", "outro", "amount", 0))
                .patch("/api/fixed-costs/" + id);
        assertJson(edited, 200, "fixed-cost");
        assertThat(edited.jsonPath().getString("kind")).isEqualTo("fixed");
        assertError(asOwner(Map.of("name", "")).post("/api/fixed-costs"), 400, "Dê um nome para o gasto.");

        // ── 3. Gasto de outra conta: 404; apaga o próprio.
        long foreign = seed.insert("INSERT INTO fixed_costs (user_id, name) VALUES (?, 'Alheio') RETURNING id",
                otherAccount());
        assertError(asOwner(Map.of("name", "Meu")).patch("/api/fixed-costs/" + foreign), 404,
                "Gasto não encontrado.");
        assertJson(asOwner().delete("/api/fixed-costs/" + id), 200, "ok");
    }

    /**
     * Ajustes: lê, grava, limita a margem mínima e recusa produção zero.
     */
    @Test
    @DisplayName("ajustes: GET, PUT, margem mínima até 95% e unidades por mês > 0")
    void settings() {

        // ── 1. Lê e grava.
        assertJson(asOwner().get("/api/settings"), 200, "settings");
        Response saved = asOwner(Map.of("hourly_rate", "18,50", "monthly_units", 200, "min_margin_pct", 120))
                .put("/api/settings");
        assertJson(saved, 200, "settings-saved");
        assertThat(saved.jsonPath().getDouble("hourly_rate")).isEqualTo(18.5);
        assertThat(saved.jsonPath().getDouble("min_margin_pct")).isEqualTo(95.0);

        // ── 2. Sem margem informada, mantém a atual.
        Response kept = asOwner(Map.of("hourly_rate", 0, "monthly_units", 100)).put("/api/settings");
        assertThat(kept.jsonPath().getDouble("min_margin_pct")).isEqualTo(95.0);
        assertError(asOwner(Map.of("monthly_units", 0)).put("/api/settings"), 400,
                "Informe quantas unidades você produz por mês (maior que zero).");

        // ── 3. Volta ao padrão para os outros testes.
        asOwner(Map.of("hourly_rate", 0, "monthly_units", 100, "min_margin_pct", 30)).put("/api/settings")
                .then().statusCode(200);
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/categories"), 401, SESSION_EXPIRED);
        assertError(api().get("/api/fixed-costs"), 401, SESSION_EXPIRED);
        assertError(api().get("/api/settings"), 401, SESSION_EXPIRED);
        assertError(api().contentType("application/json").body("{}").put("/api/settings"), 401, SESSION_EXPIRED);
        assertError(api().delete("/api/categories/1"), 401, SESSION_EXPIRED);
        assertError(api().delete("/api/fixed-costs/1"), 401, SESSION_EXPIRED);
    }
}
