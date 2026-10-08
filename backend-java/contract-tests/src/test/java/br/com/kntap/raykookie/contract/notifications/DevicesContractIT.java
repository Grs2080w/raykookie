package br.com.kntap.raykookie.contract.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractSeed.Session;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Celulares que recebem os avisos (Tarefa 4.3).
 */
class DevicesContractIT extends ContractTestBase {

    /**
     * Celular da dona: cadastro, troca de som, validações e descadastro.
     */
    @Test
    @DisplayName("/api/devices: cadastra, troca o som, valida e descadastra")
    void ownerDevices() {

        // ── 1. Cadastro e troca de som.
        Session owner = seed.owner(target.shopOwner());
        String token = "tok-dona-" + ContractSeed.uniqueEmail("x");
        assertJson(api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("token", token)).post("/api/devices"), 200, "ok");
        assertThat(seed.value("SELECT sound FROM device_tokens WHERE token = ?", token)).isEqualTo("sino");
        assertJson(api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("token", token, "sound", "alerta")).post("/api/devices"), 200, "ok");
        assertThat(seed.value("SELECT sound FROM device_tokens WHERE token = ?", token)).isEqualTo("alerta");

        // ── 2. Validações.
        assertError(api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("token", "curto")).post("/api/devices"), 400, "Token do aparelho inválido.");
        assertError(api().header("Authorization", owner.bearer()).contentType(ContentType.JSON)
                .body(Map.of("token", token, "sound", "../etc")).post("/api/devices"), 400, "Som desconhecido.");

        // ── 3. Descadastro e sem sessão.
        assertJson(api().header("Authorization", owner.bearer()).delete("/api/devices?token=" + token), 200, "ok");
        assertThat(seed.value("SELECT count(*) FROM device_tokens WHERE token = ?", token)).isEqualTo(0L);
        assertError(api().contentType(ContentType.JSON).body(Map.of("token", token)).post("/api/devices"), 401,
                "Sessão expirada. Entre novamente.");
    }

    /**
     * Celular do cliente.
     */
    @Test
    @DisplayName("/api/cliente/devices: cadastra, valida e descadastra")
    void customerDevices() {
        Session customer = seed.customer(ContractSeed.uniqueEmail("aparelho"));
        String token = "tok-cliente-" + ContractSeed.uniqueEmail("x");
        assertJson(api().header("Authorization", customer.bearer()).contentType(ContentType.JSON)
                .body(Map.of("token", token)).post("/api/cliente/devices"), 200, "ok");
        assertThat(seed.value("SELECT customer_id FROM customer_devices WHERE token = ?", token))
                .isEqualTo((int) customer.id());
        assertError(api().header("Authorization", customer.bearer()).delete("/api/cliente/devices?token=x"), 400,
                "Token do aparelho inválido.");
        assertJson(api().header("Authorization", customer.bearer()).delete("/api/cliente/devices?token=" + token),
                200, "ok");
        assertError(api().contentType(ContentType.JSON).body(Map.of("token", token)).post("/api/cliente/devices"),
                401, "Sua sessão expirou. Entre de novo para continuar.");
    }
}
