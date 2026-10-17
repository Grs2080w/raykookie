package br.com.kntap.raykookie.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.application.LinkPayments;
import br.com.kntap.raykookie.payments.application.PayLinkUseCases;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Link de pagamento com o banco real: os casos de {@code backend/tests/pay-link.integration.test.ts} que não dependem
 * do módulo de devoluções (os que dependem estão nos testes de devoluções).
 */
class PayLinkIT extends PaymentsItSupport {

    /** Casos de uso do link. */
    @Autowired
    PayLinkUseCases links;

    /** Pagamento de link aplicado. */
    @Autowired
    LinkPayments payments;

    /** Pedidos do cliente. */
    @Autowired
    OrdersApi orders;

    /** A dona. */
    OwnerId dona;

    /**
     * A dona dos testes.
     */
    @BeforeEach
    void ownerId() {
        dona = new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
    }

    /**
     * Linha registrada à mão: 2 × R$ 40 = R$ 80.
     *
     * @param status  situação
     * @param payment pagamento
     * @return o id
     */
    long seed(String status, String payment) {
        return jdbc.sql("""
                INSERT INTO orders (user_id, number, customer, product, qty, unit_price, status, payment)
                VALUES (:owner, (SELECT COALESCE(MAX(number), 0) + 1 FROM orders WHERE user_id = :owner), 'Ana',
                        'Bolo de cenoura', 2, 40, :status, :payment) RETURNING id""")
                .param("owner", dona.value()).param("status", status).param("payment", payment)
                .query(Long.class).single();
    }

    /**
     * Link de uma linha nova.
     *
     * @param cents valor (nulo = total)
     * @return linha, token e link
     */
    Seeded seedLink(Long cents) {
        long order = seed("novo", "pendente");
        String url = (String) links.ensure(dona, order, cents == null ? null : Money.ofCents(cents)).get("url");
        String token = url.substring(url.lastIndexOf('/') + 1);
        long link = jdbc.sql("SELECT id FROM order_pay_links WHERE token = :t").param("t", token).query(Long.class)
                .single();
        return new Seeded(order, token, link, null);
    }

    /**
     * Link com Pix gerado.
     *
     * @param cents valor (nulo = total)
     * @return linha, token, link e o Pix
     */
    Seeded withPix(Long cents) {
        Seeded s = seedLink(cents);
        long pix = paymentId();
        nextPix(pix);
        links.pix(s.token());
        return new Seeded(s.order(), s.token(), s.link(), String.valueOf(pix));
    }

    /**
     * Expira o Pix do link.
     *
     * @param link link
     */
    void expire(long link) {
        jdbc.sql("UPDATE order_pay_links SET pix_expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", link).update();
    }

    /**
     * Pedido do app de uma linha promovida.
     *
     * @param order linha
     * @return as colunas
     */
    Map<String, Object> shopOf(long order) {
        return jdbc.sql("""
                SELECT so.id, so.manual, so.contact_name, so.total::text AS total, so.payment_method, so.phone,
                       so.paid_mp_id, so.items::text AS items, so.public_code, so.cancelled_by, so.delivered_at,
                       so.customer_id, c.guest, c.email, c.password_hash
                  FROM orders o JOIN shop_orders so ON so.id = o.shop_order_id JOIN customers c ON c.id = so.customer_id
                 WHERE o.id = :id""").param("id", order).query().singleRow();
    }

    /**
     * Pagamento aprovado.
     *
     * @param id     id
     * @param cents  valor
     * @param status status
     * @return o pagamento
     */
    static GatewayPayment approved(String id, long cents, String status) {
        return new GatewayPayment(id, status, "", "", Money.ofCents(cents), Money.ZERO, null, null);
    }

    /**
     * Gerar o link.
     */
    @Test
    @DisplayName("ensurePayLink: total por padrão, mesma URL ao reabrir, valor de 0,01 até o total, recusas")
    void ensure() {

        // ── 1. Total por padrão e a mesma URL.
        long order = seed("novo", "sinal");
        Map<String, Object> first = links.ensure(dona, order, null);
        assertThat(first.get("amount")).isEqualTo(80L);
        assertThat((String) first.get("url")).matches("https://loja.teste/pagar/[A-Za-z0-9_-]{43}");
        assertThat(links.ensure(dona, order, null).get("url")).isEqualTo(first.get("url"));
        assertThat(links.ensure(dona, order, Money.ofCents(3050)).get("amount")).isEqualTo(30.5);
        assertThat(links.ensure(dona, order, Money.ofCents(8000)).get("amount")).isEqualTo(80L);

        // ── 2. Valor fora da faixa e linhas que não aceitam link.
        assertStatus(() -> links.ensure(dona, order, Money.ofCents(8001)), 400);
        assertStatus(() -> links.ensure(dona, order, Money.ZERO), 400);
        long other = jdbc.sql("INSERT INTO users (username, password_hash) VALUES (:u, 'x') RETURNING id")
                .param("u", "outra-" + paymentId()).query(Long.class).single();
        assertStatus(() -> links.ensure(new OwnerId(other), seed("novo", "pendente"), null), 404);
        assertStatus(() -> links.ensure(dona, 999_999_999L, null), 404);
        assertStatus(() -> links.ensure(dona, seed("novo", "pago"), null), 409);
        assertStatus(() -> links.ensure(dona, seed("cancelado", "pendente"), null), 409);
        long app = seed("novo", "pendente");
        long shop = pixOrder(paymentId());
        jdbc.sql("UPDATE orders SET shop_order_id = :so WHERE id = :id").param("so", shop).param("id", app).update();
        assertStatus(() -> links.ensure(dona, app, null), 400);
    }

    /**
     * A página do link.
     */
    @Test
    @DisplayName("payLinkView: token ruim 404; aberto com o resumo e sem Pix; pago à mão, cancelado e excluído")
    void view() {

        // ── 1. Token ruim e link novo.
        for (String bad : new String[] {"A".repeat(43), "curto", "x' OR '1'='1"}) {
            assertStatus(() -> links.view(bad), 404);
        }
        Seeded s = seedLink(5000L);
        Map<String, Object> view = links.view(s.token());
        assertThat(view).containsEntry("state", "aberto").containsEntry("amount", 50L).containsEntry("pix", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) view.get("order");
        assertThat(order).containsEntry("customer", "Ana").containsEntry("product", "Bolo de cenoura")
                .containsEntry("qty", 2L).containsEntry("unit_price", 40L).containsEntry("delivery_date", null);
    }

    /**
     * Estados da página.
     */
    @Test
    @DisplayName("payLinkView: pago à mão vira 'pago', cancelado vira 'cancelado', linha excluída leva o link")
    void viewStates() {
        Seeded s = seedLink(null);
        jdbc.sql("UPDATE orders SET payment = 'pago' WHERE id = :id").param("id", s.order()).update();
        assertThat(links.view(s.token())).containsEntry("state", "pago");
        jdbc.sql("UPDATE orders SET payment = 'pendente', status = 'cancelado' WHERE id = :id").param("id", s.order())
                .update();
        assertThat(links.view(s.token())).containsEntry("state", "cancelado");
        jdbc.sql("DELETE FROM orders WHERE id = :id").param("id", s.order()).update();
        assertStatus(() -> links.view(s.token()), 404);
    }

    /**
     * Pix do link.
     */
    @Test
    @DisplayName("payLinkPix: GET não gera; POST gera com referência, valor e e-mail; o segundo devolve o mesmo")
    void pix() {

        // ── 1. A página não gera; o POST gera.
        Seeded s = seedLink(5000L);
        links.view(s.token());
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments")));
        long pix = paymentId();
        nextPix(pix);
        Map<String, Object> r = links.pix(s.token());
        assertThat(r).containsEntry("state", "renewed");
        assertThat(map(r.get("pix"))).containsEntry("qr_base64", "iVBOR").containsEntry("expired", false);
        assertThat(((Number) map(r.get("pix")).get("expires_in")).longValue()).isGreaterThan(59 * 60 - 5);

        // ── 2. O corpo enviado e o que foi gravado.
        JsonPath body = new JsonPath(MP.findAll(postRequestedFor(urlEqualTo("/v1/payments"))).getFirst()
                .getBodyAsString());
        assertThat(body.getString("external_reference")).isEqualTo("link_" + s.link());
        assertThat(body.getDouble("transaction_amount")).isEqualTo(50.0);
        assertThat(body.getString("payer.email")).isEqualTo("pagamentos@loja.teste");
        assertThat(body.getString("payer.first_name")).isEqualTo("Ana");
        MP.verify(postRequestedFor(urlEqualTo("/v1/payments")).withHeader("X-Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("rayka-link-" + s.link() + "-1")));
        assertThat(jdbc.sql("SELECT pix_attempts || ':' || array_to_string(mp_payment_ids, ',') FROM order_pay_links "
                + "WHERE id = :id").param("id", s.link()).query(String.class).single()).isEqualTo("1:" + pix);

        // ── 3. Duplo toque: o mesmo Pix, sem criar outro.
        assertThat(links.pix(s.token())).containsEntry("state", "active");
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
        assertThat(map(links.view(s.token()).get("pix"))).containsEntry("code", "pix-" + pix);
    }

    /**
     * Pix vencido.
     */
    @Test
    @DisplayName("payLinkPix vencido: pendente é cancelado e sai outro (tentativa 2); pago no último instante vira pago")
    void expiredPix() {

        // ── 1. Pendente: cancela e gera outro.
        Seeded s = withPix(null);
        expire(s.link());
        payment(Long.parseLong(s.mp()), "pending", "link_" + s.link(), 80);
        MP.stubFor(put(urlEqualTo("/v1/payments/" + s.mp())).willReturn(json("{}")));
        long renewed = paymentId();
        nextPix(renewed);
        Map<String, Object> next = links.pix(s.token());
        assertThat(next).containsEntry("state", "renewed");
        MP.verify(putRequestedFor(urlEqualTo("/v1/payments/" + s.mp())));
        MP.verify(postRequestedFor(urlEqualTo("/v1/payments")).withHeader("X-Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("rayka-link-" + s.link() + "-2")));

        // ── 2. Pago no último instante.
        Seeded paid = withPix(null);
        expire(paid.link());
        payment(Long.parseLong(paid.mp()), "approved", "link_" + paid.link(), 80);
        assertThat(links.pix(paid.token())).isEqualTo(Map.of("ok", true, "state", "paid"));
        assertThat(value("SELECT payment FROM orders WHERE id = :id", paid.order())).isEqualTo("pago");
    }

    /**
     * Limites e recusas do Pix do link.
     */
    @Test
    @DisplayName("payLinkPix: 10 Pix 429; pago à mão 'paid'; cancelado 409; token ruim 404; valor baixou 409; MP 502")
    void pixRefusals() {

        // ── 1. Limite e estados da linha.
        Seeded many = seedLink(null);
        jdbc.sql("UPDATE order_pay_links SET pix_attempts = 10, pix_expires_at = now() - interval '1 minute', "
                + "pix_qr_code = 'X' WHERE id = :id").param("id", many.link()).update();
        assertStatus(() -> links.pix(many.token()), 429);
        Seeded paid = seedLink(null);
        jdbc.sql("UPDATE orders SET payment = 'pago' WHERE id = :id").param("id", paid.order()).update();
        assertThat(links.pix(paid.token())).isEqualTo(Map.of("ok", true, "state", "paid"));
        Seeded cancelled = seedLink(null);
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE id = :id").param("id", cancelled.order()).update();
        assertStatus(() -> links.pix(cancelled.token()), 409);
        assertStatus(() -> links.pix("A".repeat(43)), 404);
        assertStatus(() -> links.pix("curto"), 404);

        // ── 2. Valor baixou depois de compartilhar e falha do Mercado Pago.
        Seeded lower = seedLink(8000L);
        jdbc.sql("UPDATE orders SET unit_price = 30 WHERE id = :id").param("id", lower.order()).update();
        assertThatThrownBy(() -> links.pix(lower.token()))
                .hasMessage("O valor deste pedido mudou. Peça um novo link para a loja.");
        MP.verify(0, postRequestedFor(urlEqualTo("/v1/payments")));
        Seeded failing = seedLink(null);
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(500)));
        assertStatus(() -> links.pix(failing.token()), 502);
        assertThat(jdbc.sql("SELECT pix_attempts || ':' || pix_qr_code FROM order_pay_links WHERE id = :id")
                .param("id", failing.link()).query(String.class).single()).isEqualTo("0:");
    }

    /**
     * Valor trocado e conferência do Pix vencido.
     */
    @Test
    @DisplayName("mudar o valor cancela o Pix pendente; MP fora ao conferir gera o novo; aprovado com valor menor não paga")
    void amountChangeAndOldPix() {

        // ── 1. Valor novo: o Pix antigo sai e a página volta a pedir "Pagar com Pix".
        Seeded s = withPix(8000L);
        MP.stubFor(put(urlEqualTo("/v1/payments/" + s.mp())).willReturn(json("{}")));
        assertThat(links.ensure(dona, s.order(), Money.ofCents(5000)).get("amount")).isEqualTo(50L);
        MP.verify(putRequestedFor(urlEqualTo("/v1/payments/" + s.mp())));
        assertThat(links.view(s.token())).containsEntry("amount", 50L).containsEntry("pix", null);

        // ── 2. MP fora ao conferir o antigo: gera o novo mesmo assim.
        Seeded offline = withPix(8000L);
        expire(offline.link());
        long renewed = paymentId();
        nextPix(renewed);
        assertThat(links.pix(offline.token())).containsEntry("state", "renewed");

        // ── 3. Antigo aprovado com valor menor: não paga e gera o novo.
        Seeded less = withPix(8000L);
        expire(less.link());
        payment(Long.parseLong(less.mp()), "approved", "link_" + less.link(), 79);
        nextPix(paymentId());
        assertThat(links.pix(less.token())).containsEntry("state", "renewed");
        assertThat(value("SELECT payment FROM orders WHERE id = :id", less.order())).isEqualTo("pendente");
    }

    /**
     * Promoção a pedido do app.
     */
    @Test
    @DisplayName("ao pagar: convidado e pedido do app ligados, valor pago, uma linha de item, aviso à dona")
    void promotion() {

        // ── 1. Marca e promove.
        Seeded s = withPix(5000L);
        assertThat(payments.markPaid(s.link(), s.mp(), Money.ofCents(5000))).contains(s.order());
        Map<String, Object> so = shopOf(s.order());
        assertThat(so).containsEntry("guest", true).containsEntry("manual", true).containsEntry("contact_name", "Ana")
                .containsEntry("total", "50.00").containsEntry("payment_method", "pix").containsEntry("phone", "")
                .containsEntry("paid_mp_id", s.mp()).containsEntry("password_hash", null)
                .containsEntry("email", "link-" + s.link() + "@convidado.invalid");
        assertThat(so.get("items")).isEqualTo("[{\"id\": \"manual-" + s.order() + "\", \"qty\": 1, \"name\": "
                + "\"2 × Bolo de cenoura\", \"unit_price\": 50}]");
        assertThat(((Number) so.get("public_code")).intValue()).isGreaterThanOrEqualTo(1000);

        // ── 2. Linha paga, histórico e aviso.
        long shopOrder = ((Number) so.get("id")).longValue();
        assertThat(value("SELECT payment FROM orders WHERE id = :id", s.order())).isEqualTo("pago");
        assertThat(jdbc.sql("SELECT type FROM order_events WHERE shop_order_id = :id").param("id", shopOrder)
                .query(String.class).list()).contains("pago");
        assertThat(doubles.notices).anyMatch(n -> n instanceof OwnerNotice.Paid p && p.orderId() == shopOrder
                && "Ana".equals(p.customer()) && p.total().cents() == 5000 && p.items() == 1);

        // ── 3. O pedido do convidado no formato do cliente.
        Map<String, Object> view = orders.customerOrders(new CustomerId(((Number) so.get("customer_id")).longValue()))
                .getFirst();
        assertThat(view).containsEntry("manual", true).containsEntry("total", 50L).containsEntry("paid", true)
                .containsEntry("status", "novo").containsEntry("can_cancel", true);
    }

    /**
     * Quantidade fracionada e idempotência.
     */
    @Test
    @DisplayName("0,5 × produto; repetir webhook/conciliação/promoteLink não cria outro pedido nem outro convidado")
    void fractionAndIdempotence() {

        // ── 1. Quantidade fracionada.
        Seeded half = seedLink(null);
        jdbc.sql("UPDATE orders SET qty = 0.5, unit_price = 30 WHERE id = :id").param("id", half.order()).update();
        jdbc.sql("UPDATE order_pay_links SET amount = 15 WHERE id = :id").param("id", half.link()).update();
        long pix = paymentId();
        nextPix(pix);
        links.pix(half.token());
        payments.markPaid(half.link(), String.valueOf(pix), Money.ofCents(1500));
        assertThat((String) shopOf(half.order()).get("items")).contains("0,5 × Bolo de cenoura");

        // ── 2. Repetições.
        Seeded s = withPix(8000L);
        payments.markPaid(s.link(), s.mp(), Money.ofCents(8000));
        Object first = shopOf(s.order()).get("id");
        assertThat(payments.markPaid(s.link(), s.mp(), Money.ofCents(8000))).isEmpty();
        assertThat(payments.promoteLegacy(s.link())).isFalse();
        assertThat(shopOf(s.order()).get("id")).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM customers WHERE email = :e").param("e", "link-" + s.link()
                + "@convidado.invalid").query(Long.class).single()).isEqualTo(1);
    }

    /**
     * Confirmações simultâneas.
     *
     * @throws Exception se uma tarefa falhar
     */
    @Test
    @DisplayName("duas confirmações e uma promoção ao mesmo tempo: um só pedido do app e um só aviso")
    void concurrentPromotion() throws Exception {
        Seeded s = withPix(8000L);
        Callable<Object> mark = () -> payments.markPaid(s.link(), s.mp(), Money.ofCents(8000));
        Callable<Object> promote = () -> payments.promoteLegacy(s.link());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (var done : pool.invokeAll(List.of(mark, mark, promote))) {
                done.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(doubles.notices.stream().filter(n -> n instanceof OwnerNotice.Paid)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM shop_orders so JOIN orders o ON o.shop_order_id = so.id WHERE o.id = "
                + ":id").param("id", s.order()).query(Long.class).single()).isEqualTo(1);
    }

    /**
     * Pagamento fora de hora.
     */
    @Test
    @DisplayName("linha paga à mão + Pix: promove e avisa 'em dobro'; cancelada + Pix: promove cancelado e devolve")
    void lateAndDouble() {

        // ── 1. Já paga à mão: em dobro, sem devolver sozinho.
        Seeded twice = withPix(8000L);
        jdbc.sql("UPDATE orders SET payment = 'pago' WHERE id = :id").param("id", twice.order()).update();
        assertThat(payments.markPaid(twice.link(), twice.mp(), Money.ofCents(8000))).contains(twice.order());
        assertThat(doubles.notices).anyMatch(n -> n instanceof OwnerNotice.Paid p
                && p.customer().contains("pagamento em dobro"));
        assertThat(doubles.late).isEmpty();

        // ── 2. Cancelada: promove cancelado, devolução pedida, linha segue cancelada e não paga.
        Seeded cancelled = withPix(8000L);
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE id = :id").param("id", cancelled.order()).update();
        payments.markPaid(cancelled.link(), cancelled.mp(), Money.ofCents(8000));
        Map<String, Object> so = shopOf(cancelled.order());
        assertThat(so).containsEntry("cancelled_by", "loja");
        assertThat(doubles.late).containsExactly(((Number) so.get("id")).longValue());
        assertThat(value("SELECT payment FROM orders WHERE id = :id", cancelled.order())).isEqualTo("pendente");

        // ── 3. Entregue antes de pagar: o prazo de devolução começa.
        Seeded delivered = withPix(8000L);
        jdbc.sql("UPDATE orders SET status = 'entregue' WHERE id = :id").param("id", delivered.order()).update();
        payments.markPaid(delivered.link(), delivered.mp(), Money.ofCents(8000));
        assertThat(shopOf(delivered.order()).get("delivered_at")).isNotNull();
    }

    /**
     * Links antigos.
     */
    @Test
    @DisplayName("promoteLink: link antigo pago é promovido sem aviso; não pago não; cancelado sem devolução automática")
    void legacy() {
        Seeded old = withPix(8000L);
        jdbc.sql("UPDATE order_pay_links SET paid_at = now(), paid_mp_id = :mp WHERE id = :id").param("mp", old.mp())
                .param("id", old.link()).update();
        jdbc.sql("UPDATE orders SET payment = 'pago', status = 'entregue' WHERE id = :id").param("id", old.order())
                .update();
        assertThat(payments.promoteLegacy(old.link())).isTrue();
        assertThat(shopOf(old.order())).containsEntry("manual", true);
        assertThat(shopOf(old.order()).get("delivered_at")).isNotNull();
        assertThat(doubles.notices).noneMatch(n -> n instanceof OwnerNotice.Paid);
        assertThat(payments.promoteLegacy(seedLink(null).link())).isFalse();
        Seeded cancelled = withPix(8000L);
        jdbc.sql("UPDATE order_pay_links SET paid_at = now(), paid_mp_id = :mp WHERE id = :id")
                .param("mp", cancelled.mp()).param("id", cancelled.link()).update();
        jdbc.sql("UPDATE orders SET status = 'cancelado', payment = 'pago' WHERE id = :id")
                .param("id", cancelled.order()).update();
        assertThat(payments.promoteLegacy(cancelled.link())).isTrue();
        assertThat(shopOf(cancelled.order())).containsEntry("cancelled_by", "loja");
        assertThat(doubles.late).isEmpty();
    }

    /**
     * Marcar pago e o webhook do link.
     */
    @Test
    @DisplayName("markLinkPaid: valor menor ou de outro link não marca; a mais é aceito; handle de link")
    void markAndHandle() {

        // ── 1. Valor e pagamento.
        Seeded a = withPix(8000L);
        Seeded b = withPix(8000L);
        assertThat(payments.markPaid(a.link(), a.mp(), Money.ofCents(7999))).isEmpty();
        assertThat(payments.markPaid(a.link(), b.mp(), Money.ofCents(8000))).isEmpty();
        assertThat(value("SELECT payment FROM orders WHERE id = :id", a.order())).isEqualTo("pendente");
        Seeded more = withPix(5000L);
        assertThat(payments.markPaid(more.link(), more.mp(), Money.ofCents(8000))).isPresent();

        // ── 2. Webhook do link: estornado e pendente ignorados; valor menor não paga; aprovado paga uma vez.
        assertThat(payments.handle(approved(a.mp(), 8000, "refunded"), a.link())).containsEntry("status", "refunded");
        assertThat(payments.handle(approved(a.mp(), 8000, "pending"), a.link())).containsEntry("status", "pending");
        assertThat(payments.handle(approved(a.mp(), 7900, "approved"), a.link())).containsEntry("paid", false);
        assertThat(payments.handle(approved(a.mp(), 8000, "approved"), a.link())).containsEntry("paid", true);
        assertThat(payments.handle(approved(a.mp(), 8000, "approved"), a.link())).containsEntry("paid", false);
        assertThat(value("SELECT payment FROM orders WHERE id = :id", a.order())).isEqualTo("pago");

        // ── 3. Estorno de link promovido concilia; de link não promovido, não.
        payments.handle(approved(a.mp(), 8000, "refunded"), a.link());
        long shopOrder = ((Number) shopOf(a.order()).get("id")).longValue();
        assertThat(doubles.reconciled).containsExactly(shopOrder + ":" + a.mp());
        payments.handle(approved(b.mp(), 8000, "refunded"), b.link());
        assertThat(doubles.reconciled).hasSize(1);
    }

    /**
     * Conciliação dos links.
     */
    @Test
    @DisplayName("reconcileLinks: Pix aprovado com webhook perdido é aplicado; órfão cancelado é devolvido uma vez")
    void reconcile() {

        // ── 1. Webhook perdido.
        Seeded s = withPix(8000L);
        payment(Long.parseLong(s.mp()), "approved", "link:" + s.link(), 80);
        assertThat(payments.reconcile()).isGreaterThanOrEqualTo(1);
        assertThat(value("SELECT payment FROM orders WHERE id = :id", s.order())).isEqualTo("pago");

        // ── 2. Órfão: promovido cancelado e sem devolução registrada.
        Seeded orphan = withPix(8000L);
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE id = :id").param("id", orphan.order()).update();
        payments.markPaid(orphan.link(), orphan.mp(), Money.ofCents(8000));
        long shopOrder = ((Number) shopOf(orphan.order()).get("id")).longValue();
        doubles.late.clear();
        payments.reconcile();
        assertThat(doubles.late).contains(shopOrder);
    }

    /**
     * Rotas HTTP do link.
     */
    @Test
    @DisplayName("rotas: POST pagamento devolve url/valor (pt-BR); GET não gera Pix; cartão do link; cancelar pelo token")
    void routes() {

        // ── 1. A dona gera o link pela rota.
        long order = seed("novo", "pendente");
        var created = as(owner, Map.of("amount", "30,50")).post("/api/orders/" + order + "/pagamento");
        assertThat(created.jsonPath().getDouble("amount")).isEqualTo(30.5);
        assertThat(as(owner, Map.of("amount", "abc")).post("/api/orders/" + order + "/pagamento").statusCode())
                .isEqualTo(400);
        assertThat(as(owner, Map.of()).post("/api/orders/x/pagamento").statusCode()).isEqualTo(404);
        String token = created.jsonPath().getString("url").replaceAll(".*/pagar/", "");

        // ── 2. Página e Pix.
        assertThat(api().get("/api/pagar/" + token).jsonPath().getString("state")).isEqualTo("aberto");
        nextPix(paymentId());
        assertThat(api().post("/api/pagar/" + token + "/pix").jsonPath().getString("state")).isEqualTo("renewed");
        assertThat(api().get("/api/pagar/" + "A".repeat(43)).statusCode()).isEqualTo(404);

        // ── 3. Cartão do link e cancelar sem pagar.
        long approvedId = paymentId();
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + approvedId + ",\"status\":"
                + "\"approved\",\"transaction_amount\":30.5}")));
        assertThat(api().body(card("APRO")).post("/api/pagar/" + token + "/cartao").jsonPath().getBoolean("approved"))
                .isTrue();
        assertThat(api().get("/api/pagar/" + token).jsonPath().getString("shop_order.status")).isEqualTo("novo");
        assertThat(api().body(card("APRO")).post("/api/pagar/" + token + "/cartao").jsonPath().getBoolean("approved"))
                .isTrue();
        assertThat(api().body(Map.of()).post("/api/pagar/" + "B".repeat(43) + "/cancelar").statusCode())
                .isEqualTo(404);
    }

    /**
     * Cartão do link: recusa, pendente e limite.
     */
    @Test
    @DisplayName("cartão do link: recusa conta; pendente guarda o pagamento; recusado depois, 5 tentativas 429")
    void linkCard() {
        Seeded s = seedLink(null);
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + paymentId() + ",\"status\":"
                + "\"rejected\",\"status_detail\":\"cc_rejected_insufficient_amount\"}")));
        assertThat(api().body(card("FUND")).post("/api/pagar/" + s.token() + "/cartao").jsonPath()
                .getString("reason")).isEqualTo("Saldo ou limite insuficiente.");
        long pending = paymentId();
        MP.stubFor(post(urlEqualTo("/v1/payments")).willReturn(json("{\"id\":" + pending + ",\"status\":"
                + "\"in_process\"}")));
        assertThat(api().body(card("CONT")).post("/api/pagar/" + s.token() + "/cartao").jsonPath()
                .getBoolean("pending")).isTrue();
        assertThat(jdbc.sql("SELECT card_attempts || ':' || array_to_string(mp_payment_ids, ',') FROM order_pay_links "
                + "WHERE id = :id").param("id", s.link()).query(String.class).single()).isEqualTo("2:" + pending);
        MP.stubFor(get(urlEqualTo("/v1/payments/" + pending)).willReturn(json("{\"id\":" + pending + ",\"status\":"
                + "\"rejected\",\"status_detail\":\"cc_rejected_high_risk\"}")));
        jdbc.sql("UPDATE order_pay_links SET card_attempts = 5 WHERE id = :id").param("id", s.link()).update();
        assertThat(api().body(card("APRO")).post("/api/pagar/" + s.token() + "/cartao").statusCode()).isEqualTo(429);
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE id = :id").param("id", s.order()).update();
        assertThat(api().body(card("APRO")).post("/api/pagar/" + s.token() + "/cartao").statusCode()).isEqualTo(409);
        assertThat(api().body(card("APRO")).post("/api/pagar/curto/cartao").statusCode()).isEqualTo(404);
    }

    /**
     * Objeto JSON da resposta.
     *
     * @param value valor
     * @return o mapa
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * Confere o status de um erro.
     *
     * @param call   chamada
     * @param status status esperado
     */
    static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(status));
    }

    /**
     * Linha, token, link e Pix.
     *
     * @param order linha
     * @param token token
     * @param link  link
     * @param mp    Pix gerado
     */
    record Seeded(long order, String token, long link, String mp) {
    }
}
