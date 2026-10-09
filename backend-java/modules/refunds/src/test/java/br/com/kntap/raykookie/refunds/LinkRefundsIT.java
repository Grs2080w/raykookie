package br.com.kntap.raykookie.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.payments.application.LinkPayments;
import br.com.kntap.raykookie.payments.application.PayLinkUseCases;
import br.com.kntap.raykookie.refunds.application.CancelRefundUseCases;
import br.com.kntap.raykookie.refunds.application.OwnerRefundUseCases;
import io.restassured.response.Response;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Link de pagamento com o módulo de devoluções de verdade: os casos de {@code pay-link.integration.test.ts} que
 * devolvem dinheiro (cancelar e pedir devolução pelo token, devolução do pedido promovido, F2 e N2).
 */
class LinkRefundsIT extends RefundsItSupport {

    /** Link de pagamento. */
    @Autowired
    PayLinkUseCases links;

    /** Pagamento de link aplicado. */
    @Autowired
    LinkPayments linkPayments;

    /** API de pagamentos. */
    @Autowired
    PaymentsApi payments;

    /** O que a dona faz. */
    @Autowired
    OwnerRefundUseCases ownerRefunds;

    /** Cancelar devolvendo. */
    @Autowired
    CancelRefundUseCases cancel;

    /**
     * Link pago: linha à mão de 2 × R$ 40, link de R$ 80 com Pix e pagamento aplicado.
     *
     * @param status  situação da linha antes de pagar
     * @param payment pagamento da linha antes de pagar
     * @return linha, token, Pix e pedido do app
     */
    Paid paidLink(String status, String payment) {

        // ── 1. Linha e link com Pix.
        long order = jdbc.sql("""
                INSERT INTO orders (user_id, number, customer, product, qty, unit_price, status, payment)
                VALUES (:owner, :n, 'Ana', 'Bolo de cenoura', 2, 40, 'novo', 'pendente') RETURNING id""")
                .param("owner", owner.value()).param("n", NUMBER.incrementAndGet()).query(Long.class).single();
        String url = (String) links.ensure(owner, order, null).get("url");
        String token = url.substring(url.lastIndexOf('/') + 1);
        long pix = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + pix + ",\"status\":\"pending\","
                + "\"point_of_interaction\":{\"transaction_data\":{\"qr_code\":\"c\",\"qr_code_base64\":\"q\"}}}")));
        links.pix(token);

        // ── 2. A linha muda antes de o Pix chegar; o Pix é aplicado.
        jdbc.sql("UPDATE orders SET status = :s, payment = :p WHERE id = :id").param("s", status).param("p", payment)
                .param("id", order).update();
        long link = jdbc.sql("SELECT id FROM order_pay_links WHERE token = :t").param("t", token).query(Long.class)
                .single();
        linkPayments.markPaid(link, String.valueOf(pix), Money.ofCents(8000));
        Long so = jdbc.sql("SELECT shop_order_id FROM orders WHERE id = :id").param("id", order).query(Long.class)
                .single();
        return new Paid(order, token, String.valueOf(pix), so);
    }

    /**
     * Cancelar pelo token.
     */
    @Test
    @DisplayName("cancelar pelo token: pago e não iniciado cancela e devolve; outro link não age; token ruim 404")
    void cancelByToken() {
        Paid a = paidLink("novo", "pendente");
        Paid b = paidLink("novo", "pendente");
        Response r = json().body(Map.of("reason_code", "nao_preciso", "reason", "")).post("/api/pagar/" + a.token()
                + "/cancelar");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getMap("$")).containsEntry("ok", true).containsEntry("state", "cancelled")
                .containsEntry("refunded", 80);
        assertThat(lineStatus(a.order())).isEqualTo("cancelado");
        assertThat(lineStatus(b.order())).isNotEqualTo("cancelado");
        assertThat(json().body("{}").post("/api/pagar/" + "A".repeat(43) + "/cancelar").statusCode()).isEqualTo(404);
        assertThat(json().body("{}").post("/api/pagar/curto/cancelar").statusCode()).isEqualTo(404);
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + a.mp() + "/refunds")));
    }

    /**
     * Pedir devolução pelo token e desistir; a dona aprova e o estorno sai pelo pagamento do link.
     */
    @Test
    @DisplayName("pelo token: pede devolução com foto (201), desiste; de novo, a dona aprova e o estorno usa o Pix do link")
    void requestByToken() {

        // ── 1. Pede e desiste.
        Paid p = paidLink("entregue", "pendente");
        jdbc.sql("UPDATE shop_orders SET delivered_at = now() WHERE id = :id").param("id", p.shopOrder()).update();
        assertThat(ask(p).statusCode()).isEqualTo(201);
        assertThat(refundOf(p.shopOrder())).containsEntry("status", "solicitado").containsEntry("requested", "80.00");
        assertThat(api().get("/api/pagar/" + p.token()).jsonPath().getString("shop_order.refunds[0].status"))
                .isEqualTo("solicitado");
        assertThat(api().delete("/api/pagar/" + p.token() + "/devolucao").statusCode()).isEqualTo(200);
        assertThat(refundOf(p.shopOrder())).containsEntry("status", "desistiu");
        assertThat(api().multiPart("reason_code", "x").post("/api/pagar/" + "A".repeat(43) + "/devolucao")
                .statusCode()).isEqualTo(404);
        assertThat(api().delete("/api/pagar/" + "A".repeat(43) + "/devolucao").statusCode()).isEqualTo(404);

        // ── 2. Pede de novo e a dona aprova: estorno pelo pagamento do link.
        ask(p);
        long rid = ((Number) refundOf(p.shopOrder()).get("id")).longValue();
        ownerRefunds.decide(rid, owner, true, null, null, null);
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + p.mp() + "/refunds")));
    }

    /**
     * Pedido de devolução pela rota do link.
     *
     * @param p link pago
     * @return a resposta
     */
    private Response ask(Paid p) {
        return api().multiPart("reason_code", "avariado").multiPart("description", "Chegou todo amassado e derretido.")
                .multiPart("items", "[{\"id\":\"manual-" + p.order() + "\",\"qty\":1}]")
                .multiPart("fotos", "a.png", PNG, "image/png").post("/api/pagar/" + p.token() + "/devolucao");
    }

    /**
     * F2 e N2.
     */
    @Test
    @DisplayName("F2/N2: devolver tudo de pedido manual não encerra; cancelar depois não devolve de novo")
    void manualFullRefund() {

        // ── 1. F2: pagamento em dobro, devolve 80 e o pedido segue aberto.
        Paid twice = paidLink("novo", "pago");
        refundAnswer(json("{\"id\":71,\"status\":\"approved\",\"amount\":80}"));
        ownerRefunds.ownerRefund(twice.shopOrder(), owner, "80", "Pix em dobro");
        assertThat(order(twice.shopOrder())).containsEntry("refunded", "80.00").containsEntry("cancelled_at", null);
        assertThat(lineStatus(twice.order())).isEqualTo("novo");

        // ── 2. N2: a loja cancela depois, sem nova devolução; de novo é 409.
        assertThat(cancel.cancelByOwner(twice.shopOrder(), owner, "outro", "Pedido não vai acontecer"))
                .containsEntry("refundId", null).containsEntry("refunded", 0L);
        assertThat(order(twice.shopOrder())).containsEntry("cancelled_by", "loja");
        assertThat(lineStatus(twice.order())).isEqualTo("cancelado");
        assertThatThrownBy(() -> cancel.cancelByOwner(twice.shopOrder(), owner, "outro", "de novo"))
                .hasMessageContaining("cancelado");

        // ── 3. N2: o cliente cancela pelo link o pedido já devolvido, sem devolver de novo.
        Paid other = paidLink("novo", "pendente");
        ownerRefunds.ownerRefund(other.shopOrder(), owner, "80", "Pix em dobro");
        assertThat(json().body(Map.of("reason_code", "nao_preciso")).post("/api/pagar/" + other.token() + "/cancelar")
                .statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM refunds WHERE shop_order_id = :id").param("id", other.shopOrder())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(payments.linkGuest(other.token())).isPresent();
    }

    /**
     * Linha cancelada e o Pix chega; varredura dos órfãos.
     */
    @Test
    @DisplayName("linha cancelada + Pix: promove cancelado e devolve sozinho; varredura devolve o órfão uma vez")
    void lateAndOrphan() {

        // ── 1. Devolução automática.
        Paid late = paidLink("cancelado", "pendente");
        assertThat(refundOf(late.shopOrder())).containsEntry("kind", "sistema")
                .containsEntry("reason_code", "pago_apos_cancelamento").containsEntry("status", "concluido");
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + late.mp() + "/refunds")));

        // ── 2. Órfão: sem a devolução registrada, a varredura devolve uma vez.
        jdbc.sql("DELETE FROM refunds WHERE shop_order_id = :id").param("id", late.shopOrder()).update();
        jdbc.sql("UPDATE shop_orders SET refunded_total = 0 WHERE id = :id").param("id", late.shopOrder()).update();
        linkPayments.reconcile();
        linkPayments.reconcile();
        assertThat(jdbc.sql("SELECT count(*) FROM refunds WHERE shop_order_id = :id").param("id", late.shopOrder())
                .query(Long.class).single()).isEqualTo(1);
        MP.verify(2, postRequestedFor(urlPathMatching("/v1/payments/" + late.mp() + "/refunds")));
    }

    /**
     * Situação da linha.
     *
     * @param order linha
     * @return a situação
     */
    private String lineStatus(long order) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", order).query(String.class).single();
    }

    /**
     * Link pago.
     *
     * @param order     linha
     * @param token     token
     * @param mp        Pix
     * @param shopOrder pedido do app
     */
    record Paid(long order, String token, String mp, Long shopOrder) {
    }
}
