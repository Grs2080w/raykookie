package br.com.kntap.raykookie.contract.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import br.com.kntap.raykookie.contract.MercadoPagoFake;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pagamentos (Tarefa 5.2): webhook do Mercado Pago, link de pagamento, "Gerar novo Pix" e "Tentar outro cartão".
 * O Mercado Pago é o falso da suíte, com respostas escritas à mão até as gravações reais do sandbox (Tarefa 3.1).
 */
class PaymentsContractIT extends ContractTestBase {

    /** Segredo do webhook dos dois servidores sob teste. */
    private static final String SECRET = "segredo-webhook-contrato";

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /** Sessão do cliente. */
    private ContractSeed.Session customer;

    /** Item do cardápio a R$ 9,50. */
    private String item;

    /**
     * Dona, cliente e uma receita no cardápio.
     */
    @BeforeEach
    void setUp() {
        owner = seed.owner(target.shopOwner());
        customer = seed.customer(ContractSeed.uniqueEmail("pagto"));
        long recipe = seed.insert("INSERT INTO recipes (user_id, name, sale_price) VALUES (?, ?, 9.5) RETURNING id",
                owner.id(), "Cookie pago " + UUID.randomUUID().toString().substring(0, 6));
        long flour = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost) "
                + "VALUES (?, ?, 'g', 1000, 10) RETURNING id", owner.id(), "Farinha " + recipe);
        seed.exec("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (?, ?, 50)", recipe, flour);
        item = String.valueOf(recipe);
    }

    /**
     * Limpa as respostas do Mercado Pago falso.
     */
    @AfterEach
    void resetFake() {
        mercadoPago.server().resetAll();
    }

    /**
     * Id de pagamento novo.
     *
     * @return o id
     */
    private static long paymentId() {
        return ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
    }

    /**
     * O Mercado Pago cria o próximo Pix com este id.
     *
     * @param id   id do pagamento
     * @param code copia e cola
     */
    private static void nextPix(long id, String code) {
        mercadoPago.server().stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":" + id + ",\"status\":\"pending\","
                        + "\"point_of_interaction\":{\"transaction_data\":{\"qr_code\":\"" + code + "\","
                        + "\"qr_code_base64\":\"iVBOR\"}}}")));
    }

    /**
     * O Mercado Pago responde a consulta de um pagamento.
     *
     * @param id        pagamento
     * @param status    status
     * @param reference {@code external_reference}
     * @param amount    valor
     */
    private static void payment(long id, String status, String reference, double amount) {
        mercadoPago.server().stubFor(get(urlEqualTo("/v1/payments/" + id)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":" + id + ",\"status\":\"" + status
                        + "\",\"external_reference\":\"" + reference + "\",\"transaction_amount\":" + amount + "}")));
    }

    /**
     * Notificação assinada.
     *
     * @param id        pagamento
     * @param requestId {@code x-request-id}
     * @param ts        instante da assinatura
     * @return a resposta
     */
    private static Response webhook(long id, String requestId, long ts) {
        return api().contentType(ContentType.JSON)
                .header("x-signature", MercadoPagoFake.signWebhook(SECRET, String.valueOf(id), requestId, ts))
                .header("x-request-id", requestId).body(Map.of("type", "payment", "data", Map.of("id", id)))
                .post("/api/webhooks/mercadopago");
    }

    /**
     * Requisição JSON com sessão.
     *
     * @param session sessão
     * @param body    corpo
     * @return a requisição
     */
    private static RequestSpecification as(ContractSeed.Session session, Object body) {
        return api().header("Authorization", session.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Pedido com Pix do cliente (o Mercado Pago falso devolve o Pix com este id).
     *
     * @param pix id do Pix
     * @return o id do pedido
     */
    private long pixOrder(long pix) {
        nextPix(pix, "pix-" + pix);
        Map<String, Object> body = new HashMap<>(Map.of("items", List.of(Map.of("id", item, "qty", 3)),
                "contact_name", "Maria Souza", "phone", "11912345678", "fulfillment", "retirada", "time_window",
                "tarde", "payment_method", "pix"));
        body.put("scheduled_for", LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2).toString());
        Response created = as(customer, body).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        return created.jsonPath().getLong("id");
    }

    /**
     * Webhook aprovado paga o pedido; valor menor não paga.
     */
    @Test
    @DisplayName("webhook: assinatura inválida 401; aprovado paga; reenvio idempotente; valor menor não paga")
    void webhookPaysOrder() {

        // ── 1. Assinatura inválida e tipo ignorado.
        long now = Instant.now().getEpochSecond();
        assertError(api().contentType(ContentType.JSON).header("x-signature", "ts=1,v1=00").header("x-request-id",
                "r").body(Map.of("type", "payment", "data", Map.of("id", 1))).post("/api/webhooks/mercadopago"),
                401, "Assinatura inválida");
        Response ignored = api().contentType(ContentType.JSON)
                .header("x-signature", MercadoPagoFake.signWebhook(SECRET, "77", "req-i", now))
                .header("x-request-id", "req-i").body(Map.of("type", "merchant_order", "data", Map.of("id", 77)))
                .post("/api/webhooks/mercadopago");
        assertThat(ignored.jsonPath().getBoolean("ignored")).isTrue();

        // ── 2. Valor menor que o total: não paga.
        long small = paymentId();
        long order = pixOrder(small);
        payment(small, "approved", String.valueOf(order), 1.0);
        assertThat(webhook(small, UUID.randomUUID().toString(), now).statusCode()).isEqualTo(200);
        assertThat(seed.value("SELECT paid_at IS NULL FROM shop_orders WHERE id = ?", order)).isEqualTo(true);

        // ── 3. Aprovado com o total: paga; reenvio não muda nada.
        long pix = paymentId();
        long paid = pixOrder(pix);
        payment(pix, "approved", String.valueOf(paid), 28.5);
        assertThat(webhook(pix, UUID.randomUUID().toString(), now).jsonPath().getBoolean("ok")).isTrue();
        assertThat(seed.value("SELECT paid_mp_id FROM shop_orders WHERE id = ?", paid)).isEqualTo(String.valueOf(pix));
        assertThat(seed.value("SELECT payment FROM orders WHERE shop_order_id = ?", paid)).isEqualTo("pago");
        assertThat(webhook(pix, UUID.randomUUID().toString(), now).statusCode()).isEqualTo(200);
        assertThat(seed.value("SELECT count(*)::int FROM order_events WHERE shop_order_id = ? AND type = 'pago'",
                paid)).isEqualTo(1);
    }

    /**
     * G18: assinatura velha e entrega repetida.
     */
    @Test
    @ChangedFromNode("G18")
    @DisplayName("G18: ts de 11 minutos atrás é 401; o mesmo x-request-id de novo é 'duplicate'")
    void webhookReplay() {
        long pix = paymentId();
        long order = pixOrder(pix);
        payment(pix, "approved", String.valueOf(order), 28.5);
        long now = Instant.now().getEpochSecond();
        assertError(webhook(pix, "velho-" + pix, now - 11 * 60), 401, "Assinatura inválida");
        String requestId = "req-" + pix;
        assertThat(webhook(pix, requestId, now).jsonPath().getBoolean("ok")).isTrue();
        assertThat(webhook(pix, requestId, now).jsonPath().getBoolean("duplicate")).isTrue();
    }

    /**
     * Link de pagamento de ponta a ponta.
     */
    @Test
    @DisplayName("link: dona gera; página não gera Pix; POST gera e repete o mesmo; webhook paga e promove")
    void payLink() {

        // ── 1. Pedido à mão e o link.
        long order = as(owner, Map.of("customer", "Ana", "product", "Bolo", "qty", 2, "unit_price", 40))
                .post("/api/orders").jsonPath().getLong("id");
        assertError(as(owner, Map.of("amount", "100")).post("/api/orders/" + order + "/pagamento"), 400,
                "O valor precisa ficar entre R$ 0,01 e R$ 80,00.");
        Response link = as(owner, Map.of("amount", "30,50")).post("/api/orders/" + order + "/pagamento");
        assertJson(link, 200, "pay-link");
        assertThat(link.jsonPath().getDouble("amount")).isEqualTo(30.5);
        String token = link.jsonPath().getString("url").replaceAll(".*/pagar/", "");

        // ── 2. A página só lê; o POST gera o Pix e o segundo devolve o mesmo.
        Response view = api().get("/api/pagar/" + token);
        assertJson(view, 200, "pay-link-view");
        assertThat(view.jsonPath().getString("state")).isEqualTo("aberto");
        assertThat(view.jsonPath().getString("pix")).isNull();
        long pix = paymentId();
        nextPix(pix, "link-" + pix);
        Response first = api().post("/api/pagar/" + token + "/pix");
        assertJson(first, 200, "pix-state");
        assertThat(first.jsonPath().getString("state")).isEqualTo("renewed");
        assertThat(api().post("/api/pagar/" + token + "/pix").jsonPath().getString("pix.code")).isEqualTo("link-" + pix);
        assertError(api().get("/api/pagar/curto"), 404, "Link não encontrado.");

        // ── 3. O webhook paga o link e o pedido vira do app.
        long linkId = ((Number) seed.value("SELECT id FROM order_pay_links WHERE token = ?", token)).longValue();
        payment(pix, "approved", "link_" + linkId, 30.5);
        assertThat(webhook(pix, UUID.randomUUID().toString(), Instant.now().getEpochSecond()).jsonPath()
                .getBoolean("paid")).isTrue();
        Response paid = api().get("/api/pagar/" + token);
        assertThat(paid.jsonPath().getString("state")).isEqualTo("pago");
        assertThat(paid.jsonPath().getBoolean("shop_order.manual")).isTrue();
        assertThat(paid.jsonPath().getDouble("shop_order.total")).isEqualTo(30.5);
    }

    /**
     * "Gerar novo Pix".
     */
    @Test
    @DisplayName("gerar novo Pix: ainda válido devolve o mesmo; vencido desativa o antigo e gera outro")
    void regeneratePix() {

        // ── 1. Ainda válido.
        long pix = paymentId();
        long order = pixOrder(pix);
        Response active = as(customer, Map.of()).post("/api/cliente/pedidos/" + order + "/pix");
        assertJson(active, 200, "pix-state");
        assertThat(active.jsonPath().getString("state")).isEqualTo("active");
        assertThat(active.jsonPath().getString("pix.code")).isEqualTo("pix-" + pix);

        // ── 2. Vencido: confere o antigo (pendente), desativa e gera o novo.
        seed.exec("UPDATE shop_orders SET pix_expires_at = now() - interval '1 minute' WHERE id = ?", order);
        payment(pix, "pending", String.valueOf(order), 28.5);
        mercadoPago.server().stubFor(put(urlPathEqualTo("/v1/payments/" + pix)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\":" + pix + ",\"status\":\"cancelled\"}")));
        long renewed = paymentId();
        nextPix(renewed, "pix-" + renewed);
        Response next = as(customer, Map.of()).post("/api/cliente/pedidos/" + order + "/pix");
        assertThat(next.jsonPath().getString("state")).isEqualTo("renewed");
        assertThat(next.jsonPath().getString("pix.code")).isEqualTo("pix-" + renewed);
        mercadoPago.server().verify(putRequestedFor(urlPathEqualTo("/v1/payments/" + pix)));
        assertError(as(customer, Map.of()).post("/api/cliente/pedidos/999999999/pix"), 404, "Pedido não encontrado.");
    }

    /**
     * "Tentar outro cartão": validações.
     */
    @Test
    @DisplayName("tentar outro cartão: dados do cartão, CPF e pedido que não é de cartão")
    void retryCardValidation() {
        long order = pixOrder(paymentId());
        String path = "/api/cliente/pedidos/" + order + "/cartao";
        assertError(as(customer, Map.of()).post(path), 400, "Preencha os dados do cartão.");
        assertError(as(customer, Map.of("card_token", "t", "card_payment_method_id", "visa", "cpf", "111.111.111-11"))
                .post(path), 400, "Informe um CPF válido.");
        assertError(as(customer, Map.of("card_token", "t", "card_payment_method_id", "visa", "cpf", "529.982.247-25",
                "installments", 1)).post(path), 400, "Este pedido não é pago com cartão.");
    }
}
