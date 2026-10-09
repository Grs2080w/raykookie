package br.com.kntap.raykookie.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.refunds.application.RefundSender;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Rotas de devolução pela rede: telas da dona, pedido com fotos (multipart), fotos, envio simultâneo e jobs.
 */
class RefundRoutesIT extends RefundsItSupport {

    /** Envio. */
    @Autowired
    RefundSender sender;

    /** Limpeza das fotos. */
    @Autowired
    @Qualifier("cleanupOldRefundPhotosJob")
    ScheduledJob cleanup;

    /** Conciliação das devoluções processando. */
    @Autowired
    @Qualifier("reconcileProcessingRefundsJob")
    ScheduledJob reconcileProcessing;

    /** Novas tentativas. */
    @Autowired
    @Qualifier("retryDueRefundsJob")
    ScheduledJob retryDue;

    /** Token da dona. */
    String ownerToken;

    /** Token do cliente (sessão criada para o cliente semeado). */
    String customerToken;

    /**
     * Sessões da dona e do cliente.
     */
    @BeforeEach
    void sessions() {
        ownerToken = json().header("x-client", "mobile").body(Map.of("username", "dona", "password",
                "senha-de-teste-123")).post("/api/auth/login").jsonPath().getString("token");
        String email = "rt-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com";
        customerToken = json().header("x-client", "mobile").body(Map.of("name", "Maria", "email", email, "password",
                "senha-boa-123")).post("/api/cliente/cadastro").jsonPath().getString("token");
        customer = new br.com.kntap.raykookie.kernel.ids.CustomerId(jdbc.sql("SELECT id FROM customers WHERE "
                + "email = :e").param("e", email).query(Long.class).single());
    }

    /**
     * Pede devolução pela rota multipart.
     *
     * @param orderId pedido
     * @param photo   bytes da foto (nulo = sem foto)
     * @return a resposta
     */
    Response request(long orderId, byte[] photo) {
        var spec = api().header("Authorization", "Bearer " + customerToken).multiPart("reason_code", "avariado")
                .multiPart("description", "Chegou todo amassado e derretido, veja a foto.")
                .multiPart("items", "[{\"id\":\"1\",\"qty\":1}]");
        if (photo != null) {
            spec = spec.multiPart("fotos", "a.png", photo, "image/png");
        }
        return spec.post("/api/cliente/pedidos/" + orderId + "/devolucao");
    }

    /**
     * Pedido e fotos pela rede.
     */
    @Test
    @DisplayName("cliente pede com foto (201), vê a própria foto; de outro cliente 404; 2 MB + 1 byte 413; não-imagem 415")
    void customerRequest() {

        // ── 1. Pede e vê a foto.
        long id = seed("entregue", true, Instant.now());
        Response made = request(id, PNG);
        assertThat(made.statusCode()).isEqualTo(201);
        long rid = made.jsonPath().getLong("id");
        Response photo = api().header("Authorization", "Bearer " + customerToken)
                .get("/api/cliente/devolucoes/" + rid + "/fotos/1");
        assertThat(photo.statusCode()).isEqualTo(200);
        assertThat(photo.getHeader("Content-Type")).isEqualTo("image/png");
        assertThat(photo.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(api().header("Authorization", "Bearer " + customerToken).get("/api/cliente/devolucoes/" + rid
                + "/fotos/2").statusCode()).isEqualTo(404);

        // ── 2. Foto de outro cliente: 404.
        String other = json().header("x-client", "mobile").body(Map.of("name", "Outra", "email",
                "ot-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.com", "password", "senha-boa-123"))
                .post("/api/cliente/cadastro").jsonPath().getString("token");
        assertThat(api().header("Authorization", "Bearer " + other).get("/api/cliente/devolucoes/" + rid + "/fotos/1")
                .statusCode()).isEqualTo(404);

        // ── 3. Limites e tipos: 2 MB + 1 byte, não-imagem, itens ruins, sem multipart; desistir.
        long next = seed("entregue", true, Instant.now());
        byte[] big = new byte[2_000_001];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(request(next, big).statusCode()).isEqualTo(413);
        assertThat(request(next, "texto qualquer, nao imagem".getBytes()).statusCode()).isEqualTo(415);
        assertThat(api().header("Authorization", "Bearer " + customerToken).multiPart("items", "{ruim")
                .post("/api/cliente/pedidos/" + next + "/devolucao").statusCode()).isEqualTo(400);
        assertThat(json().header("Authorization", "Bearer " + customerToken).body("{}")
                .post("/api/cliente/pedidos/" + next + "/devolucao").statusCode()).isEqualTo(400);
        assertThat(api().header("Authorization", "Bearer " + customerToken).delete("/api/cliente/pedidos/" + id
                + "/devolucao").jsonPath().getBoolean("ok")).isTrue();
        assertThat(api().header("Authorization", "Bearer " + customerToken).delete("/api/cliente/pedidos/x"
                + "/devolucao").statusCode()).isEqualTo(404);
    }

    /**
     * Telas da dona.
     */
    @Test
    @DisplayName("dona: lista por grupo com contagens, detalhe com fotos e histórico, foto, decisão, devolver e tentar")
    void ownerScreens() {

        // ── 1. Uma solicitação aguardando.
        long id = seed("entregue", true, Instant.now());
        long rid = request(id, PNG).jsonPath().getLong("id");
        Response list = api().header("Authorization", "Bearer " + ownerToken).get("/api/devolucoes");
        assertThat(list.jsonPath().getList("list.id", Long.class)).contains(rid);
        assertThat(list.jsonPath().getInt("counts.aguardando")).isGreaterThanOrEqualTo(1);
        assertThat(api().header("Authorization", "Bearer " + ownerToken).get("/api/devolucoes?status=negadas")
                .jsonPath().getList("list.id", Long.class)).doesNotContain(rid);

        // ── 2. Detalhe e foto.
        Response detail = api().header("Authorization", "Bearer " + ownerToken).get("/api/devolucoes/" + rid);
        assertThat(detail.jsonPath().getString("reason")).isEqualTo("Chegou amassado, quebrado ou derretido");
        assertThat(detail.jsonPath().getString("photos[0].url")).isEqualTo("/api/devolucoes/" + rid + "/fotos/1");
        assertThat(detail.jsonPath().getList("events.type")).contains("devolucao_solicitada");
        assertThat(api().header("Authorization", "Bearer " + ownerToken).get("/api/devolucoes/" + rid + "/fotos/1")
                .statusCode()).isEqualTo(200);
        assertThat(api().header("Authorization", "Bearer " + ownerToken).get("/api/devolucoes/999999999")
                .statusCode()).isEqualTo(404);

        // ── 3. Decide, devolve um valor e tenta de novo.
        assertThat(json().header("Authorization", "Bearer " + ownerToken).body(Map.of("approve", true))
                .post("/api/devolucoes/" + rid + "/decisao").jsonPath().getString("status")).isEqualTo("aprovado");
        assertThat(json().header("Authorization", "Bearer " + ownerToken).post("/api/devolucoes/" + rid + "/tentar")
                .statusCode()).isEqualTo(409);
        long other = seed("producao");
        assertThat(json().header("Authorization", "Bearer " + ownerToken).body(Map.of("amount", "5", "note", "Faltou"))
                .post("/api/orders/shop/" + other + "/devolver").jsonPath().getDouble("refunded")).isEqualTo(5.0);
        assertThat(json().header("Authorization", "Bearer " + ownerToken).post("/api/orders/shop/" + other + "/devolver")
                .statusCode()).isEqualTo(400);
        assertThat(json().header("Authorization", "Bearer " + ownerToken).post("/api/devolucoes/" + rid + "/decisao")
                .statusCode()).isEqualTo(409);
    }

    /**
     * A mesma devolução enviada duas vezes ao mesmo tempo.
     *
     * @throws Exception se uma tarefa falhar
     */
    @Test
    @DisplayName("a mesma devolução mandada duas vezes ao mesmo tempo: um só POST /refunds no Mercado Pago")
    void concurrentSend() throws Exception {
        long id = seed("producao");
        long rid = jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount,
                                     approved_amount, decided_at)
                VALUES (:so, :owner, :customer, 'loja', 'aprovado', 'outro', 30, 30, now()) RETURNING id""")
                .param("so", id).param("owner", owner.value()).param("customer", customer.value()).query(Long.class)
                .single();
        refundAnswer(json("{\"id\":991,\"status\":\"approved\",\"amount\":30}").withFixedDelay(800));
        Callable<String> send = () -> sender.send(rid);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var both = pool.invokeAll(List.of(send, send));
            assertThat(List.of(both.get(0).get(), both.get(1).get())).containsExactlyInAnyOrder("concluido",
                    "ignorado");
        } finally {
            pool.shutdown();
        }
        MP.verify(1, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(order(id)).containsEntry("refunded", "30.00");
    }

    /**
     * Jobs.
     */
    @Test
    @DisplayName("jobs: fotos de decisões antigas somem; processando é conciliado; retry-due roda")
    void jobs() {

        // ── 1. Foto antiga.
        long id = seed("entregue", true, Instant.now());
        long rid = request(id, PNG).jsonPath().getLong("id");
        jdbc.sql("UPDATE refunds SET status = 'negado', decided_at = now() - interval '181 days' WHERE id = :id")
                .param("id", rid).update();
        cleanup.run();
        assertThat(jdbc.sql("SELECT count(*) FROM refund_photos WHERE refund_id = :id").param("id", rid)
                .query(Long.class).single()).isZero();

        // ── 2. Processando com resposta: concilia.
        long other = seed("producao");
        jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount,
                                     approved_amount, decided_at, mp_refund_id)
                VALUES (:so, :owner, :customer, 'loja', 'processando', 'outro', 20, 20, now(), '555')""")
                .param("so", other).param("owner", owner.value()).param("customer", customer.value()).update();
        MP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/v1/payments/" + lastPayment
                + "/refunds")).willReturn(json("[{\"id\":555,\"status\":\"approved\",\"amount\":20}]")));
        reconcileProcessing.run();
        assertThat(order(other)).containsEntry("refunded", "20.00");
        assertThat(refundOf(other)).containsEntry("status", "concluido");
        retryDue.run();
        assertThat(retryDue.name()).isEqualTo("refunds.retry-due");
    }
}
