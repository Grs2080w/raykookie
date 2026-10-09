package br.com.kntap.raykookie.contract.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Endereços de retirada: {@code /api/pickup-addresses} (dona) e {@code /api/pickup} (público) (Tarefa 4.6).
 */
class PickupContractIT extends ContractTestBase {

    /** Mensagem de rota da dona sem sessão. */
    private static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /**
     * Abre a sessão da dona e começa sem endereços (o limite é de 20 por conta).
     */
    @BeforeEach
    void openSession() {
        owner = seed.owner(target.shopOwner());
        seed.exec("DELETE FROM pickup_addresses WHERE user_id = ?", owner.id());
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification asOwner(Object body) {
        return api().header("Authorization", owner.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Requisição sem corpo como a dona.
     *
     * @return a requisição
     */
    private RequestSpecification asOwner() {
        return api().header("Authorization", owner.bearer());
    }

    /**
     * Um endereço válido.
     *
     * @param street rua
     * @return o corpo
     */
    private static Map<String, Object> address(String street) {
        Map<String, Object> body = new HashMap<>(Map.of("street", street, "number", "120", "district", "Jardim",
                "city", "São Paulo", "cep", "01234-567", "complement", "apto 4", "instructions", "Portão azul"));
        body.put("label", "");
        return body;
    }

    /**
     * Cadastro: o primeiro vira o ativo e aparece no endereço público.
     */
    @Test
    @DisplayName("o primeiro endereço vira o ativo e aparece em /api/pickup")
    void firstBecomesActive() {

        // ── 1. Sem endereço: público vazio.
        Response empty = api().get("/api/pickup");
        assertJson(empty, 200, "pickup");
        assertThat(empty.jsonPath().getString("address")).isEmpty();
        assertThat(empty.getHeader("Cache-Control")).isEqualTo("public, max-age=30");

        // ── 2. Primeiro endereço: ativo, com o apelido padrão (a rua) e o endereço formatado.
        Response created = asOwner(address("Rua das Flores")).post("/api/pickup-addresses");
        assertJson(created, 201, "pickup-address");
        assertThat(created.jsonPath().getBoolean("is_active")).isTrue();
        assertThat(created.jsonPath().getString("label")).isEqualTo("Rua das Flores");
        assertThat(created.jsonPath().getString("cep")).isEqualTo("01234567");
        assertThat(created.jsonPath().getString("address"))
                .isEqualTo("Rua das Flores, 120 (apto 4) - Jardim, São Paulo");

        // ── 3. O público mostra o ativo.
        Response pickup = api().get("/api/pickup");
        assertThat(pickup.jsonPath().getString("label")).isEqualTo("Rua das Flores");
        assertThat(pickup.jsonPath().getString("instructions")).isEqualTo("Portão azul");
    }

    /**
     * Troca do ativo, edição e exclusão.
     */
    @Test
    @DisplayName("troca do ativo, edição, exclusão do ativo (409 com outros) e lista ordenada")
    void switchEditAndDelete() {

        // ── 1. Dois endereços; o segundo vira o ativo.
        long first = asOwner(address("Rua A")).post("/api/pickup-addresses").jsonPath().getLong("id");
        long second = asOwner(address("Rua B")).post("/api/pickup-addresses").jsonPath().getLong("id");
        Response activated = asOwner(Map.of("active", true)).patch("/api/pickup-addresses/" + second);
        assertJson(activated, 200, "pickup-address");
        Response list = asOwner().get("/api/pickup-addresses");
        assertJson(list, 200, "pickup-addresses");
        assertThat(list.jsonPath().getList("id", Long.class)).containsExactly(second, first);

        // ── 2. Edição de um campo valida o formulário inteiro.
        assertError(asOwner(Map.of("street", "Rua C")).patch("/api/pickup-addresses/" + first), 400,
                "Informe o número (ou S/N, se não tiver).");
        Map<String, Object> edited = address("Rua C");
        edited.put("label", "Ateliê");
        assertThat(asOwner(edited).patch("/api/pickup-addresses/" + first).jsonPath().getString("label"))
                .isEqualTo("Ateliê");

        // ── 3. O ativo não sai enquanto há outro; o outro sai.
        assertError(asOwner().delete("/api/pickup-addresses/" + second), 409,
                "Este é o endereço de retirada atual. Escolha outro como atual antes de excluir este.");
        assertJson(asOwner().delete("/api/pickup-addresses/" + first), 200, "ok");
        assertJson(asOwner().delete("/api/pickup-addresses/" + second), 200, "ok");
    }

    /**
     * Validações e endereço de outra conta.
     */
    @Test
    @DisplayName("validação do formulário e endereço de outra conta")
    void validation() {
        Map<String, Object> body = address("Rua A");
        body.put("cep", "123");
        assertError(asOwner(body).post("/api/pickup-addresses"), 400, "O CEP precisa ter 8 números.");
        assertError(asOwner(Map.of()).post("/api/pickup-addresses"), 400, "Informe a rua.");
        assertError(asOwner(Map.of("street", "R", "number", "1")).post("/api/pickup-addresses"), 400,
                "Informe o bairro.");
        assertError(asOwner(Map.of("street", "R", "number", "1", "district", "D")).post("/api/pickup-addresses"),
                400, "Informe a cidade.");
        long other = seed.insert("INSERT INTO users (username, password_hash) VALUES (?, 'x') RETURNING id",
                "outra-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        long foreign = seed.insert("INSERT INTO pickup_addresses (user_id, label, street, number, district, city) "
                + "VALUES (?, 'x', 'x', '1', 'x', 'x') RETURNING id", other);
        assertError(asOwner(Map.of("active", true)).patch("/api/pickup-addresses/" + foreign), 404,
                "Endereço não encontrado.");
        assertJson(asOwner().delete("/api/pickup-addresses/" + foreign), 200, "ok");
        assertThat(seed.value("SELECT count(*)::int FROM pickup_addresses WHERE id = ?", foreign)).isEqualTo(1);
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/pickup-addresses"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/pickup-addresses"), 401,
                SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").patch("/api/pickup-addresses/1"), 401,
                SESSION_EXPIRED);
        assertError(api().delete("/api/pickup-addresses/1"), 401, SESSION_EXPIRED);
    }
}
