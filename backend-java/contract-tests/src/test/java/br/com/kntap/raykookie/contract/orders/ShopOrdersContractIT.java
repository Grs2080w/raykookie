package br.com.kntap.raykookie.contract.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pedidos da loja pelo cliente: {@code /api/cliente/pedidos} e o cancelamento (Tarefa 5.1).
 */
class ShopOrdersContractIT extends ContractTestBase {

    /** Sessão do cliente. */
    private ContractSeed.Session customer;

    /** Item do cardápio (id da receita, em texto). */
    private String item;

    /**
     * Um cliente novo e uma receita no cardápio a R$ 9,50.
     */
    @BeforeEach
    void setUp() {
        ContractSeed.Session owner = seed.owner(target.shopOwner());
        customer = seed.customer(ContractSeed.uniqueEmail("pedido"));
        long recipe = seed.insert("INSERT INTO recipes (user_id, name, sale_price) VALUES (?, ?, 9.5) RETURNING id",
                owner.id(), "Cookie do pedido " + UUID.randomUUID().toString().substring(0, 6));
        long flour = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost) "
                + "VALUES (?, ?, 'g', 1000, 10) RETURNING id", owner.id(), "Farinha " + recipe);
        seed.exec("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (?, ?, 50)", recipe, flour);
        item = String.valueOf(recipe);
    }

    /**
     * Requisição JSON como o cliente.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification asCustomer(Object body) {
        return api().header("Authorization", customer.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Um pedido válido, para depois de amanhã.
     *
     * @param payment forma de pagamento
     * @return o corpo
     */
    private Map<String, Object> order(String payment) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("id", item, "qty", 3, "unit_price", 0.01)));
        body.put("contact_name", "Maria Souza");
        body.put("phone", "(11) 91234-5678");
        body.put("fulfillment", "retirada");
        body.put("scheduled_for", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString());
        body.put("time_window", "tarde");
        body.put("payment_method", payment);
        return body;
    }

    /**
     * Pedido em dinheiro: total pelo cardápio do servidor, linha na tela Pedidos e na lista do cliente.
     */
    @Test
    @DisplayName("POST em dinheiro: 201, preço do cardápio (não o do corpo), aparece para a dona e para o cliente")
    void cashOrder() {

        // ── 1. Cria: o preço manipulado no corpo é ignorado.
        Response created = asCustomer(order("dinheiro")).post("/api/cliente/pedidos");
        assertJson(created, 201, "shop-order-created");
        long id = created.jsonPath().getLong("id");
        assertThat(created.jsonPath().getInt("code")).isBetween(1000, 99999);
        assertThat(seed.value("SELECT total::text FROM shop_orders WHERE id = ?", id)).isEqualTo("28.50");

        // ── 2. A linha da tela Pedidos.
        assertThat(seed.value("SELECT product FROM orders WHERE shop_order_id = ?", id).toString())
                .startsWith("Cookie do pedido");
        assertThat(seed.value("SELECT notes FROM orders WHERE shop_order_id = ?", id).toString())
                .contains("Pedido online #").contains("Pagamento: Dinheiro");

        // ── 3. A lista do cliente.
        Response list = api().header("Authorization", customer.bearer()).get("/api/cliente/pedidos");
        assertJson(list, 200, "customer-orders");
        JsonPath json = new JsonPath(list.asString()).setRootPath("find { it.id == " + id + " }");
        assertThat(json.getDouble("total")).isEqualTo(28.5);
        assertThat(json.getBoolean("can_cancel")).isTrue();
        assertThat(json.getString("status")).isEqualTo("novo");
        assertThat(json.getString("payment_state")).isEqualTo("pendente");
        assertThat(json.getString("refund.mode")).isEqualTo("nenhum");
    }

    /**
     * Validações do pedido.
     */
    @Test
    @DisplayName("validação: item fora do cardápio, nome, telefone, data e troco")
    void validation() {
        Map<String, Object> body = order("dinheiro");
        body.put("items", List.of(Map.of("id", "nao-existe", "qty", 1)));
        assertError(asCustomer(body).post("/api/cliente/pedidos"), 400, "Um dos itens escolhidos não está mais no cardápio.");
        body = order("dinheiro");
        body.put("phone", "123");
        assertError(asCustomer(body).post("/api/cliente/pedidos"), 400,
                "Informe um telefone com DDD. Só usamos se precisarmos falar com você sobre o pedido.");
        body = order("dinheiro");
        body.put("scheduled_for", LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString());
        assertError(asCustomer(body).post("/api/cliente/pedidos"), 400,
                "Fazemos tudo sob encomenda: escolha uma data com pelo menos 1 dia de antecedência.");
        body = order("dinheiro");
        body.put("change_for", "10");
        assertError(asCustomer(body).post("/api/cliente/pedidos"), 400,
                "O troco precisa ser para um valor maior ou igual ao total do pedido.");
        assertError(asCustomer(Map.of()).post("/api/cliente/pedidos"), 400, "Escolha pelo menos um item do cardápio.");
    }

    /**
     * Pix: o Mercado Pago (falso) devolve o copia e cola.
     */
    @Test
    @DisplayName("POST com Pix: gera a cobrança no Mercado Pago e devolve o copia e cola")
    void pixOrder() {

        // ── 1. O Mercado Pago responde a criação do Pix.
        mercadoPago.server().stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":987654321,\"status\":\"pending\",\"external_reference\":\"1\","
                        + "\"transaction_amount\":28.5,\"point_of_interaction\":{\"transaction_data\":"
                        + "{\"qr_code\":\"00020126pix-copia-e-cola\",\"qr_code_base64\":\"iVBORw0KGgo=\"}}}")));

        // ── 2. O pedido sai com o Pix e o pagamento fica guardado.
        Response created = asCustomer(order("pix")).post("/api/cliente/pedidos");
        assertJson(created, 201, "shop-order-created");
        assertThat(created.jsonPath().getString("pix.code")).isEqualTo("00020126pix-copia-e-cola");
        assertThat(created.jsonPath().getInt("pix.expires_in")).isGreaterThan(3000);
        long id = created.jsonPath().getLong("id");
        assertThat(seed.value("SELECT mp_payment_id FROM shop_orders WHERE id = ?", id)).isEqualTo("987654321");

        // ── 3. O pedido enviado ao Mercado Pago.
        mercadoPago.server().verify(postRequestedFor(urlEqualTo("/v1/payments"))
                .withHeader("X-Idempotency-Key", equalTo("rayka-order-" + id + "-1")));
        mercadoPago.server().resetAll();
    }

    /**
     * Cancelamento pelo cliente, antes da produção.
     */
    @Test
    @DisplayName("cancelar: pedido novo é cancelado; de novo é 'already'; em produção 409; de outro cliente 404")
    void cancel() {

        // ── 1. Cancela um pedido em dinheiro ainda novo.
        long id = asCustomer(order("dinheiro")).post("/api/cliente/pedidos").jsonPath().getLong("id");
        Response cancelled = asCustomer(Map.of("reason_code", "data_horario")).post("/api/cliente/pedidos/" + id
                + "/cancelar");
        assertJson(cancelled, 200, "cancel-result");
        assertThat(cancelled.jsonPath().getString("state")).isEqualTo("cancelled");
        assertThat(seed.value("SELECT cancel_reason FROM shop_orders WHERE id = ?", id))
                .isEqualTo("A data ou o horário não servem mais");
        assertThat(asCustomer(Map.of()).post("/api/cliente/pedidos/" + id + "/cancelar").jsonPath().getString("state"))
                .isEqualTo("already");

        // ── 2. Em produção, não cancela por aqui.
        long started = asCustomer(order("dinheiro")).post("/api/cliente/pedidos").jsonPath().getLong("id");
        seed.exec("UPDATE orders SET status = 'producao' WHERE shop_order_id = ?", started);
        assertError(asCustomer(Map.of()).post("/api/cliente/pedidos/" + started + "/cancelar"), 409,
                "Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. Fale com a gente.");

        // ── 3. Motivo "outro" exige texto; pedido de outro cliente é 404.
        assertError(asCustomer(Map.of("reason_code", "outro", "reason", "x")).post("/api/cliente/pedidos/" + started
                + "/cancelar"), 400, "Conte o motivo do cancelamento.");
        ContractSeed.Session other = seed.customer(ContractSeed.uniqueEmail("outro"));
        assertError(api().header("Authorization", other.bearer()).contentType(ContentType.JSON).body("{}")
                .post("/api/cliente/pedidos/" + started + "/cancelar"), 404, "Pedido não encontrado.");
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão de cliente: 401")
    void withoutSession() {
        assertThat(api().get("/api/cliente/pedidos").statusCode()).isEqualTo(401);
        assertThat(api().contentType(ContentType.JSON).body("{}").post("/api/cliente/pedidos").statusCode())
                .isEqualTo(401);
        assertThat(api().contentType(ContentType.JSON).body("{}").post("/api/cliente/pedidos/1/cancelar").statusCode())
                .isEqualTo(401);
    }
}
