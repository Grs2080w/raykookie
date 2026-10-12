package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Pix e cartão de ponta a ponta com a API Orders do Mercado Pago ligada ({@code MP_API=orders}): o id guardado no
 * pedido é o do pedido do Mercado Pago ({@code ORD…}), o webhook é do tipo {@code order} e o cartão pode pedir o
 * desafio 3DS.
 */
@TestPropertySource(properties = {"rayka.mp.api=orders"})
class OrdersApiIT extends PaymentsItSupport {

    /**
     * Pedido da API Orders em JSON.
     *
     * @param id     id do pedido ({@code ORD…})
     * @param ref    {@code external_reference}
     * @param status status do pedido
     * @param detail motivo do status
     * @param extra  trecho extra do pagamento (ex.: {@code payment_method})
     * @return o JSON
     */
    private static String orderJson(String id, String ref, String status, String detail, String extra) {
        return "{\"id\":\"" + id + "\",\"external_reference\":\"" + ref + "\",\"total_amount\":\"19.00\","
                + "\"status\":\"" + status + "\",\"status_detail\":\"" + detail + "\",\"transactions\":{\"payments\":"
                + "[{\"id\":\"PAY" + id + "\",\"amount\":\"19.00\",\"status\":\"" + status + "\",\"status_detail\":\""
                + detail + "\"" + extra + "}]}}";
    }

    /**
     * GET autenticado como cliente.
     *
     * @param token sessão do cliente
     * @param path  caminho
     * @return a resposta
     */
    private Response getAs(String token, String path) {
        return api().header("Authorization", "Bearer " + token).get(path);
    }

    /**
     * Pix: nasce na API Orders e é confirmado pelo webhook do tipo {@code order}.
     */
    @Test
    @DisplayName("Pix: guarda o id ORD…; o webhook 'order' assinado com o id em minúsculas paga o pedido")
    void pixConfirmedByOrderWebhook() {

        // ── 1. Pedido com Pix na API Orders.
        String ord = "ORDTST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json("{\"id\":\"" + ord
                + "\",\"status\":\"action_required\",\"status_detail\":\"waiting_transfer\",\"total_amount\":\"19.00\",\"transactions\":{\"payments\":"
                + "[{\"id\":\"PAY1\",\"amount\":\"19.00\",\"status\":\"action_required\",\"payment_method\":{"
                + "\"id\":\"pix\",\"qr_code\":\"000201pix\",\"qr_code_base64\":\"iVBOR\"}}]}}")));
        long order = as(customer, order("pix")).post("/api/cliente/pedidos").jsonPath().getLong("id");
        assertThat(value("SELECT mp_payment_id FROM shop_orders WHERE id = :id", order)).isEqualTo(ord);
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders")));

        // ── 2. Webhook do tipo order: consulta o pedido e paga.
        MP.stubFor(get(urlEqualTo("/v1/orders/" + ord)).willReturn(json(orderJson(ord, String.valueOf(order),
                "processed", "accredited", ""))));
        Response hook = signedWebhook(ord.toLowerCase(), UUID.randomUUID().toString(),
                Map.of("type", "order", "action", "order.processed", "data", Map.of("id", ord)));
        assertThat(hook.statusCode()).isEqualTo(200);
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order)).isEqualTo(ord);
        assertThat(doubles.notices).anyMatch(n -> n instanceof OwnerNotice.Paid p && p.orderId() == order);
    }

    /**
     * Cartão com desafio 3DS: devolve o endereço, e a consulta de status acompanha até aprovar.
     */
    @Test
    @DisplayName("cartão com 3DS: devolve challenge_url; a consulta segue pendente e depois aprova e paga o pedido")
    void cardWithThreeDsChallenge() {

        // ── 1. O Mercado Pago pede o desafio.
        String ord = "ORDTST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        String challenge = ",\"payment_method\":{\"id\":\"visa\",\"type\":\"credit_card\",\"transaction_security\":"
                + "{\"url\":\"https://www.mercadopago.com.br/auth/card/validation/pages/remedies/abc\"}}";
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(orderJson(ord, "x", "action_required",
                "pending_challenge", challenge))));
        Response created = cardOrder("tok-3ds");
        long order = created.jsonPath().getLong("id");
        assertThat(created.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", true,
                "challenge_url", "https://www.mercadopago.com.br/auth/card/validation/pages/remedies/abc"));
        assertThat(value("SELECT paid_at IS NULL FROM shop_orders WHERE id = :id", order)).isEqualTo(true);

        // ── 2. Desafio ainda aberto: segue pendente, com o mesmo endereço.
        MP.stubFor(get(urlEqualTo("/v1/orders/" + ord)).willReturn(json(orderJson(ord, String.valueOf(order),
                "action_required", "pending_challenge", challenge))));
        Map<String, Object> waiting = getAs(customer, "/api/cliente/pedidos/" + order + "/cartao")
                .jsonPath().getMap("$");
        assertThat(waiting).containsEntry("pending", true).containsKey("challenge_url");

        // ── 2b. "Tentar pagar" de novo com o desafio aberto: devolve o mesmo desafio, sem cobrar outra vez.
        Response retry = as(customer, card("tok-3ds-2")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.jsonPath().getMap("$")).containsEntry("pending", true).containsKey("challenge_url");
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/orders")));

        // ── 3. Outro cliente não enxerga o pedido.
        assertThat(getAs(signUp(), "/api/cliente/pedidos/" + order + "/cartao").statusCode())
                .isEqualTo(404);

        // ── 4. Desafio concluído: aprovado, pedido pago com o id ORD….
        MP.stubFor(get(urlEqualTo("/v1/orders/" + ord)).willReturn(json(orderJson(ord, String.valueOf(order),
                "processed", "accredited", ""))));
        Response done = getAs(customer, "/api/cliente/pedidos/" + order + "/cartao");
        assertThat(done.jsonPath().getMap("$")).isEqualTo(Map.of("approved", true));
        assertThat(value("SELECT paid_mp_id FROM shop_orders WHERE id = :id", order)).isEqualTo(ord);
        assertThat(doubles.notices).anyMatch(n -> n instanceof OwnerNotice.Paid p && p.orderId() == order);
    }

    /**
     * Cartão recusado (HTTP 402 do Mercado Pago) e desafio que expirou.
     */
    @Test
    @DisplayName("recusa em HTTP 402 vira recusa com o texto em português; desafio expirado também recusa")
    void refusedAndExpired() {

        // ── 1. Recusa por saldo.
        String ord = "ORDTST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json("{\"errors\":[{\"code\":\"failed\"}],\"data\":"
                + orderJson(ord, "x", "failed", "insufficient_amount", "") + "}").withStatus(402)));
        Response refused = cardOrder("tok-fund");
        assertThat(refused.statusCode()).isEqualTo(201);
        assertThat(refused.jsonPath().getMap("card")).isEqualTo(Map.of("approved", false, "pending", false,
                "reason", "Saldo ou limite insuficiente."));

        // ── 2. Desafio que o comprador não concluiu: a consulta já devolve a recusa.
        String ord2 = "ORDTST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(orderJson(ord2, "x", "action_required",
                "pending_challenge", ",\"payment_method\":{\"transaction_security\":{\"url\":\"https://x/y\"}}"))));
        long order = cardOrder("tok-exp").jsonPath().getLong("id");
        MP.stubFor(get(urlEqualTo("/v1/orders/" + ord2)).willReturn(json(orderJson(ord2, String.valueOf(order),
                "expired", "expired", ""))));
        Map<String, Object> status = getAs(customer, "/api/cliente/pedidos/" + order + "/cartao")
                .jsonPath().getMap("$");
        assertThat(status).containsEntry("approved", false).containsEntry("pending", false).containsKey("reason");
    }
}
