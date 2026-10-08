package br.com.kntap.raykookie.contract.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tela Pedidos da dona: {@code /api/orders} e {@code /api/orders/{id}}, inclusive as linhas de pedido do app
 * (os 4 casos de {@code backend/tests/orders-route.integration.test.ts}, agora com sessão de verdade) (Tarefa 5.1).
 */
class ManualOrdersContractIT extends ContractTestBase {

    /** Mensagem de rota da dona sem sessão. */
    private static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /** Cancelamento de pedido do app só pelo botão próprio. */
    private static final String USE_CANCEL = "Pedido do app: use \"Cancelar pedido\", que pede o motivo para a cliente "
            + "e devolve o Pix se já foi pago.";

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /**
     * Abre a sessão da dona.
     */
    @BeforeEach
    void openSession() {
        owner = seed.owner(target.shopOwner());
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
     * Uma linha de pedido do app (pedido da loja pago), direto no banco.
     *
     * @param cancelled se o pedido já foi encerrado
     * @return o id da linha em {@code orders}
     */
    private long appLine(boolean cancelled) {
        long customer = seed.insert("INSERT INTO customers (email, name) VALUES (?, 'Maria') RETURNING id",
                "p-" + UUID.randomUUID() + "@x.com");
        long shopOrder = seed.insert("INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, "
                + "scheduled_for, time_window, payment_method, items, total, public_code, paid_at, cancelled_at, "
                + "cancelled_by) VALUES (?, ?, 'Maria', '11999999999', 'retirada', '2026-10-20', 'tarde', 'pix', "
                + "'[{\"id\":\"1\",\"name\":\"Cookie\",\"qty\":1,\"unit_price\":6}]', 6, "
                + "(SELECT 10000 + floor(random() * 89999)::int), now(), "
                + (cancelled ? "now(), 'sistema'" : "NULL, NULL") + ") RETURNING id", customer, owner.id());
        return seed.insert("INSERT INTO orders (user_id, number, customer, product, qty, unit_price, status, payment, "
                + "shop_order_id) VALUES (?, (SELECT COALESCE(MAX(number), 0) + 1 FROM orders WHERE user_id = ?), 'Maria', "
                + "'Cookie', 1, 6, ?, 'pago', ?) RETURNING id", owner.id(), owner.id(),
                cancelled ? "cancelado" : "producao", shopOrder);
    }

    /**
     * Um pedido registrado à mão.
     *
     * @return o corpo
     */
    private static Map<String, Object> manual() {
        Map<String, Object> body = new HashMap<>();
        body.put("customer", "Ana");
        body.put("product", "Bolo de cenoura");
        body.put("qty", "2");
        body.put("unit_price", "45,50");
        body.put("delivery_date", "2026-12-20");
        body.put("notes", "sem cobertura");
        return body;
    }

    /**
     * Pedido registrado à mão: cria, lista, edita e exclui.
     */
    @Test
    @DisplayName("pedido à mão: POST 201 com número seguinte, GET lista, PATCH e DELETE")
    void manualOrder() {

        // ── 1. Cria: número seguinte, data de hoje e status padrão.
        Response created = asOwner(manual()).post("/api/orders");
        assertJson(created, 201, "manual-order");
        assertThat(created.jsonPath().getDouble("unit_price")).isEqualTo(45.5);
        assertThat(created.jsonPath().getString("status")).isEqualTo("novo");
        assertThat(created.jsonPath().getString("payment")).isEqualTo("pendente");
        long id = created.jsonPath().getLong("id");
        Response list = asOwner().get("/api/orders");
        assertJson(list, 200, "orders");
        assertThat(list.jsonPath().getList("id", Long.class)).contains(id);

        // ── 2. Troca rápida e edição completa.
        assertJson(asOwner(Map.of("payment", "sinal")).patch("/api/orders/" + id), 200, "manual-order");
        Map<String, Object> edited = manual();
        edited.put("status", "pronto");
        edited.put("customer", "Ana Paula");
        Response full = asOwner(edited).patch("/api/orders/" + id);
        assertThat(full.jsonPath().getString("customer")).isEqualTo("Ana Paula");
        assertThat(full.jsonPath().getString("status")).isEqualTo("pronto");

        // ── 3. Exclui.
        assertJson(asOwner().delete("/api/orders/" + id), 200, "ok");
        assertError(asOwner(Map.of("payment", "pago")).patch("/api/orders/" + id), 404, "Pedido não encontrado.");
    }

    /**
     * Validações do pedido à mão.
     */
    @Test
    @DisplayName("pedido à mão: cliente, produto, quantidade e data inválidos")
    void validation() {
        Map<String, Object> body = manual();
        body.put("customer", "");
        assertError(asOwner(body).post("/api/orders"), 400, "Informe o nome do cliente.");
        body = manual();
        body.put("product", " ");
        assertError(asOwner(body).post("/api/orders"), 400, "Informe o produto.");
        body = manual();
        body.put("qty", 0);
        assertError(asOwner(body).post("/api/orders"), 400, "A quantidade precisa ser maior que zero.");
        body = manual();
        body.put("delivery_date", "2026-02-31");
        assertError(asOwner(body).post("/api/orders"), 400, "Data inválida.");
    }

    /**
     * Linha de pedido do app não é excluída; pedido à mão é.
     */
    @Test
    @DisplayName("linha de pedido do app não pode ser excluída (409)")
    void appLineIsNotDeleted() {
        long line = appLine(false);
        Response response = asOwner().delete("/api/orders/" + line);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("error")).contains("não pode ser excluído");
        assertThat(seed.value("SELECT count(*)::int FROM orders WHERE id = ?", line)).isEqualTo(1);
    }

    /**
     * Pedido do app encerrado não reabre.
     */
    @Test
    @DisplayName("pedido do app encerrado: status travado (409)")
    void closedAppLineIsLocked() {
        long line = appLine(true);
        assertError(asOwner(Map.of("status", "producao")).patch("/api/orders/" + line), 409,
                "Este pedido do app já foi encerrado (cancelado ou devolvido) e não muda mais.");
        assertThat(seed.value("SELECT status FROM orders WHERE id = ?", line)).isEqualTo("cancelado");
    }

    /**
     * Troca rápida em linha do app: pagamento e status valem; cancelar não.
     */
    @Test
    @DisplayName("linha do app: troca de pagamento e status funciona, cancelar e mudar item/valor é 409")
    void appLineQuickChanges() {

        // ── 1. Troca rápida.
        long line = appLine(false);
        assertThat(asOwner(Map.of("payment", "sinal")).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        assertThat(asOwner(Map.of("status", "pronto")).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        assertError(asOwner(Map.of("status", "cancelado")).patch("/api/orders/" + line), 409, USE_CANCEL);

        // ── 2. Edição completa sem mudar item e valor; mudar é 409.
        Map<String, Object> full = new HashMap<>(Map.of("customer", "Maria", "product", "Cookie", "qty", 1,
                "unit_price", 6, "status", "pronto", "payment", "pago", "notes", "buscar às 15h"));
        assertThat(asOwner(full).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        full.put("qty", 3);
        assertThat(asOwner(full).patch("/api/orders/" + line).statusCode()).isEqualTo(409);
        full.put("qty", 1);
        full.put("unit_price", 1);
        assertThat(asOwner(full).patch("/api/orders/" + line).statusCode()).isEqualTo(409);
        assertThat(seed.value("SELECT notes FROM orders WHERE id = ?", line)).isEqualTo("buscar às 15h");
    }

    /**
     * Pedido de outra conta: 404.
     */
    @Test
    @DisplayName("pedido de outra conta: PATCH 404 e DELETE não apaga")
    void otherAccount() {
        long other = seed.insert("INSERT INTO users (username, password_hash) VALUES (?, 'x') RETURNING id",
                "outra-" + UUID.randomUUID().toString().substring(0, 8));
        long id = seed.insert("INSERT INTO orders (user_id, number, customer, product) VALUES (?, 1, 'X', 'Y') "
                + "RETURNING id", other);
        assertError(asOwner(Map.of("payment", "pago")).patch("/api/orders/" + id), 404, "Pedido não encontrado.");
        assertError(asOwner(manual()).patch("/api/orders/" + id), 404, "Pedido não encontrado.");
        asOwner().delete("/api/orders/" + id);
        assertThat(seed.value("SELECT count(*)::int FROM orders WHERE id = ?", id)).isEqualTo(1);
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().get("/api/orders"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/orders"), 401, SESSION_EXPIRED);
        assertError(api().contentType(ContentType.JSON).body("{}").patch("/api/orders/1"), 401, SESSION_EXPIRED);
        assertError(api().delete("/api/orders/1"), 401, SESSION_EXPIRED);
    }
}
