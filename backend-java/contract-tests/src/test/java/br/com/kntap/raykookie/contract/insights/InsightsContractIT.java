package br.com.kntap.raykookie.contract.insights;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Painel e metas (Tarefa 6.2) e a lista de downloads (Tarefa 6.3).
 */
class InsightsContractIT extends ContractTestBase {

    /**
     * Painel, metas e downloads.
     */
    @Test
    @DisplayName("painel e metas: formatos; meta gravada volta no GET; mês inválido 400; downloads sem cache")
    void dashboardGoalsDownloads() {

        // ── 1. Painel e metas.
        ContractSeed.Session owner = seed.owner(target.shopOwner());
        assertJson(api().header("Authorization", owner.bearer()).get("/api/dashboard"), 200, "dashboard");
        Response saved = api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("month", "2025-02-01", "revenue", "2.500,00", "orders", 40, "new_customers", 5,
                        "avg_ticket", 62.5)).put("/api/goals");
        assertThat(saved.statusCode()).isEqualTo(200);
        Response goals = api().header("Authorization", owner.bearer()).get("/api/goals?month=2025-02-01");
        assertJson(goals, 200, "goals");
        assertThat(goals.jsonPath().getDouble("goal.revenue")).isEqualTo(2500.0);
        assertError(api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("month", "2025-02-10")).put("/api/goals"), 400, "Mês inválido.");

        // ── 2. Downloads: lista pública, sem cache.
        Response downloads = api().get("/api/downloads");
        assertThat(downloads.statusCode()).isEqualTo(200);
        assertThat(downloads.getHeader("Cache-Control")).isEqualTo("no-store");
        assertError(api().get("/api/downloads/outro"), 404, "App não encontrado.");
    }
}
