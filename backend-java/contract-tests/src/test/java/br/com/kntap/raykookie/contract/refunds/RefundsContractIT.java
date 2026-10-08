package br.com.kntap.raykookie.contract.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Devoluções (Tarefa 5.3): cancelar e devolver pela dona, devolver um valor, pedido do cliente com foto, telas da
 * dona, decisão, fotos e o cancelamento de pedido pago pelo cliente. O Mercado Pago falso aprova as devoluções.
 */
class RefundsContractIT extends ContractTestBase {

    /** PNG mínimo. */
    private static final byte[] PNG = png();

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /** Sessão do cliente. */
    private ContractSeed.Session customer;

    /**
     * Dona, cliente e o Mercado Pago aprovando devoluções.
     */
    @BeforeEach
    void setUp() {
        owner = seed.owner(target.shopOwner());
        customer = seed.customer(ContractSeed.uniqueEmail("devol"));
        mercadoPago.server().stubFor(post(urlPathMatching("/v1/payments/.*/refunds")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":" + ThreadLocalRandom.current().nextInt(1, 1_000_000) + ",\"status\":\"approved\","
                        + "\"amount\":100}")));
    }

    /**
     * Limpa o falso.
     */
    @AfterEach
    void reset() {
        mercadoPago.server().resetAll();
    }

    /**
     * PNG com a assinatura.
     *
     * @return os bytes
     */
    private static byte[] png() {
        byte[] bytes = new byte[48];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, bytes, 0, 8);
        return bytes;
    }

    /**
     * Pedido de R$ 100 do cliente, pago, com as duas linhas na situação indicada.
     *
     * @param status    situação
     * @param delivered se já foi entregue (agora)
     * @return o id
     */
    private long paidOrder(String status, boolean delivered) {
        String mp = "pay" + UUID.randomUUID().toString().substring(0, 8);
        long so = seed.insert("INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, "
                + "scheduled_for, time_window, payment_method, items, total, public_code, paid_at, paid_mp_id, "
                + "mp_payment_ids, delivered_at) VALUES (?, ?, 'Maria', '11999999999', 'retirada', '2026-10-20', 'tarde', "
                + "'pix', '[{\"id\":\"1\",\"name\":\"Brownie\",\"qty\":2,\"unit_price\":30},{\"id\":\"2\",\"name\":"
                + "\"Torta\",\"qty\":1,\"unit_price\":40}]', 100, (SELECT 10000 + floor(random() * 89999)::int), now(), ?, "
                + "ARRAY[?], " + (delivered ? "now()" : "NULL") + ") RETURNING id", customer.id(), owner.id(), mp, mp);
        for (String product : new String[] {"Brownie", "Torta"}) {
            seed.exec("INSERT INTO orders (user_id, number, customer, product, qty, unit_price, status, payment, "
                    + "shop_order_id) VALUES (?, (SELECT COALESCE(MAX(number), 0) + 1 FROM orders WHERE user_id = ?), "
                    + "'Maria', ?, 1, 10, ?, 'pago', ?)", owner.id(), owner.id(), product, status, so);
        }
        return so;
    }

    /**
     * Requisição JSON da dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification asOwner(Object body) {
        return api().header("Authorization", owner.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Cancelamento pela dona e pelo cliente.
     */
    @Test
    @DisplayName("cancelar: a dona cancela pago e devolve tudo (motivo da lista); o cliente cancela pago não iniciado")
    void cancel() {

        // ── 1. Pela dona.
        long id = paidOrder("producao", false);
        assertError(asOwner(Map.of("code", "inventado")).post("/api/orders/shop/" + id + "/cancelar"), 400,
                "Escolha o motivo do cancelamento.");
        Response cancelled = asOwner(Map.of("code", "falta_ingrediente")).post("/api/orders/shop/" + id + "/cancelar");
        assertJson(cancelled, 200, "refund-result");
        assertThat(cancelled.jsonPath().getDouble("refunded")).isEqualTo(100.0);
        assertThat(seed.value("SELECT cancelled_by FROM shop_orders WHERE id = ?", id)).isEqualTo("loja");
        assertError(asOwner(Map.of("code", "falta_ingrediente")).post("/api/orders/shop/" + id + "/cancelar"), 409,
                "Este pedido já está cancelado.");

        // ── 2. Pelo cliente.
        long mine = paidOrder("novo", false);
        Response byCustomer = api().header("Authorization", customer.bearer()).contentType(ContentType.JSON)
                .body(Map.of("reason_code", "preco")).post("/api/cliente/pedidos/" + mine + "/cancelar");
        assertJson(byCustomer, 200, "cancel-result");
        assertThat(byCustomer.jsonPath().getDouble("refunded")).isEqualTo(100.0);
    }

    /**
     * Devolver um valor.
     */
    @Test
    @DisplayName("devolver valor: 40 devolve; acima do restante 400 com o valor; sem motivo 400")
    void ownerRefund() {
        long id = paidOrder("producao", false);
        Response refunded = asOwner(Map.of("amount", "40", "note", "Faltou a torta"))
                .post("/api/orders/shop/" + id + "/devolver");
        assertJson(refunded, 200, "refund-result");
        assertThat(refunded.jsonPath().getDouble("refunded")).isEqualTo(40.0);
        assertError(asOwner(Map.of("amount", "70", "note", "demais")).post("/api/orders/shop/" + id + "/devolver"),
                400, "O valor passa do que ainda pode ser devolvido (R$ 60,00).");
        assertError(asOwner(Map.of("amount", "1")).post("/api/orders/shop/" + id + "/devolver"), 400,
                "Conte o motivo da devolução.");
    }

    /**
     * Solicitação do cliente com foto, telas da dona e decisão.
     */
    @Test
    @DisplayName("solicitação com foto: 201; lista e detalhe da dona; fotos; negar; desistir sem solicitação é 409")
    void requestAndDecide() {

        // ── 1. O cliente pede com foto.
        long id = paidOrder("entregue", true);
        Response made = api().header("Authorization", customer.bearer()).multiPart("reason_code", "avariado")
                .multiPart("description", "Chegou todo amassado e derretido, veja a foto.")
                .multiPart("items", "[{\"id\":\"1\",\"qty\":1}]").multiPart("fotos", "a.png", PNG, "image/png")
                .post("/api/cliente/pedidos/" + id + "/devolucao");
        assertJson(made, 201, "refund-created");
        long rid = made.jsonPath().getLong("id");

        // ── 2. Telas da dona e fotos.
        Response list = api().header("Authorization", owner.bearer()).get("/api/devolucoes");
        assertJson(list, 200, "refund-list");
        assertThat(list.jsonPath().getList("list.id", Long.class)).contains(rid);
        Response detail = api().header("Authorization", owner.bearer()).get("/api/devolucoes/" + rid);
        assertJson(detail, 200, "refund-detail");
        assertThat(detail.jsonPath().getDouble("requested_amount")).isEqualTo(30.0);
        assertThat(api().header("Authorization", owner.bearer()).get("/api/devolucoes/" + rid + "/fotos/1")
                .getHeader("Content-Type")).isEqualTo("image/png");
        assertThat(api().header("Authorization", customer.bearer()).get("/api/cliente/devolucoes/" + rid + "/fotos/1")
                .statusCode()).isEqualTo(200);
        assertThat(api().header("Authorization", customer.bearer()).get("/api/cliente/devolucoes/" + rid + "/fotos/3")
                .statusCode()).isEqualTo(404);

        // ── 3. Nega; desistir depois é 409; tentar de novo uma negada é 409.
        Response denied = asOwner(Map.of("approve", false, "code", "fora_prazo", "note", "Passou do prazo"))
                .post("/api/devolucoes/" + rid + "/decisao");
        assertJson(denied, 200, "refund-decision");
        assertThat(denied.jsonPath().getString("status")).isEqualTo("negado");
        assertError(api().header("Authorization", customer.bearer()).delete("/api/cliente/pedidos/" + id
                + "/devolucao"), 409, "Não há solicitação em análise para cancelar.");
        assertError(asOwner(Map.of()).post("/api/devolucoes/" + rid + "/tentar"), 409,
                "Esta devolução não precisa ser refeita.");
        assertError(asOwner(Map.of("approve", true)).post("/api/devolucoes/" + rid + "/decisao"), 409,
                "Esta solicitação já foi decidida.");
    }
}
