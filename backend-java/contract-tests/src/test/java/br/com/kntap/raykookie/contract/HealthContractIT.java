package br.com.kntap.raykookie.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/health}: o primeiro contrato, igual no Node e no Java.
 */
class HealthContractIT extends ContractTestBase {

    /**
     * Com o banco no ar: 200 {@code {"ok":true}}.
     */
    @Test
    @DisplayName("GET /api/health com banco no ar: 200 {\"ok\":true}")
    void healthy() {

        // ── 1. Pergunta pela rede.
        Response response = api().get("/api/health");

        // ── 2. 200, JSON no formato do schema, ok = true.
        assertJson(response, 200, "health");
        assertThat(response.jsonPath().getBoolean("ok")).isTrue();
    }
}
