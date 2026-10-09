package br.com.kntap.raykookie.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.refunds.application.CancelRefundUseCases;
import br.com.kntap.raykookie.refunds.application.CustomerRefundUseCases;
import br.com.kntap.raykookie.refunds.application.OwnerRefundUseCases;
import br.com.kntap.raykookie.refunds.application.RefundSender;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

/**
 * Devoluções com o banco real: os casos de {@code backend/tests/refunds.integration.test.ts} (o do faturamento do
 * mês fica nos testes de insights).
 */
class RefundsIT extends RefundsItSupport {

    /** Cancelar devolvendo. */
    @Autowired
    CancelRefundUseCases cancel;

    /** O que a dona faz. */
    @Autowired
    OwnerRefundUseCases ownerRefunds;

    /** O que o cliente faz. */
    @Autowired
    CustomerRefundUseCases customerRefunds;

    /** Envio. */
    @Autowired
    RefundSender sender;

    /** Pedidos do cliente. */
    @Autowired
    OrdersApi orders;

    /** Métricas (alertas). */
    @Autowired
    MeterRegistry meters;

    /**
     * Pedido de devolução.
     *
     * @param reason      motivo
     * @param description descrição
     * @param items       itens
     * @param photos      fotos
     * @return o formulário
     */
    static RefundRequestForm form(String reason, String description, List<Map<String, Object>> items, int photos) {
        return RefundRequestForm.of(reason, description, items, java.util.Collections.nCopies(photos,
                new RefundRequestForm.Photo("image/png", PNG)));
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
     * Pago e não iniciado.
     */
    @Test
    @DisplayName("pago e não iniciado: cancela e devolve o total na hora, uma única vez")
    void cancelPaid() {
        long id = seed("novo");
        assertThat(cancel.cancelPaidByCustomer(id, customer, "errei_pedido", "")).containsEntry("refunded", 100L);
        long refundId = ((Number) refundOf(id).get("id")).longValue();
        MP.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + lastPayment + "/refunds"))
                .withHeader("X-Idempotency-Key", equalTo("rayka-refund-" + refundId)).withRequestBody(equalToJson("{}")));
        assertThat(order(id)).containsEntry("refunded", "100.00").containsEntry("cancelled_by", "cliente")
                .containsEntry("cancel_reason_code", "errei_pedido");
        assertThat(refundOf(id)).containsEntry("kind", "automatico").containsEntry("status", "concluido")
                .containsEntry("approved", "100.00");
        assertThat(jdbc.sql("SELECT status FROM orders WHERE shop_order_id = :id").param("id", id).query(String.class)
                .list()).containsOnly("cancelado");
        assertThat(events(id)).contains("cancelado", "devolucao_aprovada", "devolucao_concluida");
        assertStatus(() -> cancel.cancelPaidByCustomer(id, customer, "errei_pedido", ""), 409);
        MP.verify(1, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
    }

    /**
     * Em produção e de outro cliente.
     */
    @Test
    @DisplayName("pago em produção: recusado e nada devolvido; pedido de outro cliente: 404")
    void productionAndOther() {
        long id = seed("producao");
        assertStatus(() -> cancel.cancelPaidByCustomer(id, customer, "preco", ""), 409);
        MP.verify(0, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(order(id)).containsEntry("cancelled_at", null).containsEntry("refunded", "0.00");
        assertStatus(() -> cancel.cancelPaidByCustomer(seed("novo"), new CustomerId(customer.value() + 999), "preco",
                ""), 404);
    }

    /**
     * Solicitação e aprovação parcial.
     */
    @Test
    @DisplayName("solicitação: valida motivo, descrição, itens e fotos; aprova parcial e devolve só o aprovado")
    void requestAndPartial() {

        // ── 1. Validações.
        long id = seed("producao");
        String text = "O brownie chegou seco e duro, não dá para comer.";
        List<Map<String, Object>> one = List.of(Map.of("id", "1", "qty", 1));
        assertStatus(() -> form("xxx", text, one, 1), 400);
        assertStatus(() -> form("qualidade", "curto", one, 1), 400);
        assertStatus(() -> customerRefunds.request(id, customer, form("qualidade", text,
                List.of(Map.of("id", "1", "qty", 5)), 1)), 400);
        assertStatus(() -> customerRefunds.request(id, customer, form("qualidade", text, List.of(), 1)), 400);
        assertStatus(() -> form("qualidade", text, one, 6), 400);

        // ── 2. Pede: R$ 30, uma foto, aviso à dona; só uma por pedido.
        customerRefunds.request(id, customer, form("qualidade", text, one, 1));
        Map<String, Object> row = refundOf(id);
        long rid = ((Number) row.get("id")).longValue();
        assertThat(row).containsEntry("kind", "solicitado").containsEntry("status", "solicitado")
                .containsEntry("requested", "30.00").containsEntry("reason_code", "qualidade");
        assertThat(jdbc.sql("SELECT count(*) FROM refund_photos WHERE refund_id = :id").param("id", rid)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(log.notices).anyMatch(n -> n instanceof OwnerNotice.Refund r && "solicitada".equals(r.kind())
                && r.amount().cents() == 3000);
        assertStatus(() -> customerRefunds.request(id, customer, form("qualidade", text, one, 1)), 409);

        // ── 3. Parcial exige explicação; acima do restante é recusado; aprova 10.
        assertStatus(() -> ownerRefunds.decide(rid, owner, true, 10, null, null), 400);
        assertStatus(() -> ownerRefunds.decide(rid, owner, true, 150, null, "x"), 400);
        MP.verify(0, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        refundAnswer(json("{\"id\":901,\"status\":\"approved\",\"amount\":10}"));
        assertThat(ownerRefunds.decide(rid, owner, true, 10, null, "Devolvemos parte do valor"))
                .containsEntry("status", "aprovado");
        MP.verify(postRequestedFor(urlEqualTo("/v1/payments/" + lastPayment + "/refunds"))
                .withHeader("X-Idempotency-Key", equalTo("rayka-refund-" + rid))
                .withRequestBody(equalToJson("{\"amount\":10.00}")));
        assertThat(refundOf(id)).containsEntry("status", "concluido").containsEntry("approved", "10.00");
        assertThat(order(id)).containsEntry("refunded", "10.00").containsEntry("cancelled_at", null);
        assertStatus(() -> ownerRefunds.decide(rid, owner, false, null, "fora_prazo", null), 409);
    }

    /**
     * Negar.
     */
    @Test
    @DisplayName("negar exige um motivo da lista e não chama o Mercado Pago")
    void deny() {
        long id = seed("pronto");
        customerRefunds.request(id, customer, form("desisti", "Não vou mais precisar do pedido, desculpe.",
                List.of(Map.of("id", "2", "qty", 1)), 0));
        long rid = ((Number) refundOf(id).get("id")).longValue();
        assertStatus(() -> ownerRefunds.decide(rid, owner, false, null, "inventado", null), 400);
        assertStatus(() -> ownerRefunds.decide(rid, owner, false, null, "outro", ""), 400);
        assertThat(ownerRefunds.decide(rid, owner, false, null, "desistencia_producao", "Já estava pronto"))
                .containsEntry("status", "negado");
        MP.verify(0, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(refundOf(id)).containsEntry("status", "negado").containsEntry("decision_code", "desistencia_producao");
        assertStatus(() -> ownerRefunds.decide(rid + 999_999, owner, false, null, "outro", "x"), 404);
    }

    /**
     * Entregue: fotos, prazo e desistência.
     */
    @Test
    @DisplayName("entregue: fotos obrigatórias; depois de 48h não aceita; desistir libera nova solicitação")
    void delivered() {
        long id = seed("entregue", true, Instant.now());
        String text = "Chegou todo amassado e derretido, veja a foto.";
        List<Map<String, Object>> one = List.of(Map.of("id", "1", "qty", 1));
        assertStatus(() -> customerRefunds.request(id, customer, form("avariado", text, one, 0)), 400);
        customerRefunds.request(id, customer, form("avariado", text, one, 1));
        assertThat(customerRefunds.withdraw(id, customer)).isEqualTo(Map.of("ok", true));
        assertStatus(() -> customerRefunds.withdraw(id, customer), 409);
        customerRefunds.request(id, customer, form("avariado", text, one, 1));
        long late = seed("entregue", true, Instant.now().minus(Duration.ofHours(49)));
        assertStatus(() -> customerRefunds.request(late, customer, form("avariado", text, one, 1)), 409);
    }

    /**
     * Sem saldo.
     */
    @Test
    @DisplayName("sem saldo: 'falhou' com nova tentativa marcada, aviso à dona e alerta; o botão refaz com a mesma chave")
    void noBalance() {

        // ── 1. Falha por saldo.
        long id = seed("novo");
        double alerts = meters.find("rayka_alerts").counters().stream().mapToDouble(c -> c.count()).sum();
        refundAnswer(aResponse().withStatus(428).withBody("{\"message\":\"insufficient_money_for_refund\"}"));
        cancel.cancelPaidByCustomer(id, customer, "preco", "");
        Map<String, Object> f = refundOf(id);
        assertThat(f).containsEntry("status", "falhou").containsEntry("fail_reason", "saldo")
                .containsEntry("attempts", 1);
        assertThat(f.get("next_try_at")).isNotNull();
        assertThat(log.notices).anyMatch(n -> n instanceof OwnerNotice.Refund r && "saldo".equals(r.kind())
                && r.amount().cents() == 10_000);
        assertThat(meters.find("rayka_alerts").counters().stream().mapToDouble(c -> c.count()).sum())
                .isGreaterThan(alerts);
        assertThat(order(id)).containsEntry("refunded", "0.00");

        // ── 2. Refaz: concluída, com a mesma chave.
        long rid = ((Number) f.get("id")).longValue();
        refundAnswer(json("{\"id\":950,\"status\":\"approved\",\"amount\":100}"));
        assertThat(ownerRefunds.retry(rid, owner)).containsEntry("status", "concluido");
        assertThat(order(id)).containsEntry("refunded", "100.00");
        MP.verify(2, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds"))
                .withHeader("X-Idempotency-Key", equalTo("rayka-refund-" + rid)));
        assertStatus(() -> ownerRefunds.retry(rid, owner), 409);
        assertStatus(() -> ownerRefunds.retry(rid + 999_999, owner), 404);
        assertThat(log.notices).anyMatch(n -> n instanceof OwnerNotice.Refund r && "concluida".equals(r.kind()));
    }

    /**
     * Job de novas tentativas.
     */
    @Test
    @DisplayName("job: refaz as devoluções cuja hora de tentar chegou")
    void retryDue() {
        long id = seed("novo");
        refundAnswer(aResponse().withStatus(500).withBody("boom"));
        cancel.cancelPaidByCustomer(id, customer, "preco", "");
        assertThat(refundOf(id)).containsEntry("status", "falhou").containsEntry("fail_reason", "erro");
        long rid = ((Number) refundOf(id).get("id")).longValue();
        jdbc.sql("UPDATE refunds SET next_try_at = now() + interval '1 hour' WHERE status = 'falhou'").update();
        assertThat(sender.retryDue()).isZero();
        jdbc.sql("UPDATE refunds SET next_try_at = now() - interval '1 minute' WHERE id = :id").param("id", rid).update();
        refundAnswer(json("{\"id\":960,\"status\":\"approved\",\"amount\":100}"));
        assertThat(sender.retryDue()).isGreaterThanOrEqualTo(1);
        assertThat(refundOf(id)).containsEntry("status", "concluido");
    }

    /**
     * Cancelamento pela loja.
     */
    @Test
    @DisplayName("cancelamento pela loja: pago devolve tudo; exige motivo da lista; 'outro' exige texto")
    void ownerCancel() {
        long id = seed("producao");
        assertStatus(() -> cancel.cancelByOwner(id, owner, "inventado", ""), 400);
        assertStatus(() -> cancel.cancelByOwner(id, owner, "outro", ""), 400);
        assertThat(cancel.cancelByOwner(id, owner, "falta_ingrediente", "")).containsEntry("refunded", 100L);
        assertThat(order(id)).containsEntry("cancelled_by", "loja").containsEntry("refunded", "100.00");
        assertThat(refundOf(id)).containsEntry("kind", "loja").containsEntry("status", "concluido");
        long delivered = seed("entregue", true, Instant.now());
        assertStatus(() -> cancel.cancelByOwner(delivered, owner, "falta_ingrediente", ""), 409);
    }

    /**
     * Devolver valor sem cancelar.
     */
    @Test
    @DisplayName("a loja devolve um valor sem cancelar; não passa do que resta; devolver tudo encerra o pedido")
    void ownerRefund() {
        long id = seed("producao");
        refundAnswer(json("{\"id\":970,\"status\":\"approved\",\"amount\":40}"));
        assertThat(ownerRefunds.ownerRefund(id, owner, "40", "Faltou a torta")).containsEntry("refunded", 40L);
        assertThat(order(id)).containsEntry("refunded", "40.00").containsEntry("cancelled_at", null);
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "70", "demais"), 400);
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "", "sem valor"), 400);
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "1", ""), 400);
        refundAnswer(json("{\"id\":971,\"status\":\"approved\",\"amount\":60}"));
        ownerRefunds.ownerRefund(id, owner, "60,00", "resto");
        assertThat(order(id)).containsEntry("refunded", "100.00").containsEntry("cancelled_by", "sistema")
                .containsEntry("cancel_reason_code", "devolucao_total");
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "1", "mais um"), 409);
        assertStatus(() -> ownerRefunds.ownerRefund(seed("novo", false, null), owner, "1", "não pago"), 409);
    }

    /**
     * Valor em pt-BR.
     */
    @Test
    @DisplayName("valor digitado em pt-BR: '1.000' é mil (não R$ 1) e '12,5' é 12,50")
    void localeAmounts() {
        long id = seed("producao");
        assertThatThrownBy(() -> ownerRefunds.ownerRefund(id, owner, "1.000", "Faltou")).hasMessageContaining(
                "passa do que ainda pode");
        assertThatThrownBy(() -> ownerRefunds.ownerRefund(id, owner, "1.000,00", "Faltou")).hasMessageContaining(
                "passa");
        refundAnswer(json("{\"id\":972,\"status\":\"approved\",\"amount\":12.5}"));
        assertThat(ownerRefunds.ownerRefund(id, owner, "12,5", "Faltou um item")).containsEntry("refunded", 12.5);
        long other = seed("entregue", true, Instant.now());
        customerRefunds.request(other, customer, form("avariado", "Chegou todo amassado e derretido.",
                List.of(Map.of("id", "1", "qty", 1)), 1));
        long rid = ((Number) refundOf(other).get("id")).longValue();
        assertThatThrownBy(() -> ownerRefunds.decide(rid, owner, true, "1.000", null, "Devolvemos parte"))
                .hasMessageContaining("passa");
        assertStatus(() -> ownerRefunds.decide(rid, owner, true, "0", null, "x"), 400);
    }

    /**
     * Caso #38118.
     */
    @Test
    @DisplayName("caso #38118: devolução total aprovada encerra; antes e depois, cancelar ou devolver é recusado")
    void case38118() {

        // ── 1. Solicitação aguardando: a loja decide por ela.
        long id = seed("producao");
        customerRefunds.request(id, customer, form("desisti", "Não vou mais precisar do pedido, desculpe.",
                List.of(Map.of("id", "1", "qty", 2), Map.of("id", "2", "qty", 1)), 0));
        long rid = ((Number) refundOf(id).get("id")).longValue();
        assertThatThrownBy(() -> cancel.cancelByOwner(id, owner, "falta_ingrediente", ""))
                .hasMessageContaining("aguardando");
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "10", "por fora"), 409);
        assertStatus(() -> cancel.cancelPaidByCustomer(id, customer, "preco", ""), 409);

        // ── 2. Aprova tudo: encerra pelo sistema.
        assertThat(ownerRefunds.decide(rid, owner, true, null, null, null)).containsEntry("status", "aprovado");
        MP.verify(1, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(order(id)).containsEntry("refunded", "100.00").containsEntry("cancelled_by", "sistema")
                .containsEntry("cancel_reason_code", "devolucao_total")
                .containsEntry("cancel_reason", "Todo o valor foi devolvido");
        assertThat(jdbc.sql("SELECT status FROM orders WHERE shop_order_id = :id").param("id", id).query(String.class)
                .list()).containsExactly("cancelado", "cancelado");

        // ── 3. Depois: nada muda.
        assertStatus(() -> cancel.cancelByOwner(id, owner, "falta_ingrediente", "tentando de novo"), 409);
        assertStatus(() -> ownerRefunds.ownerRefund(id, owner, "1", "mais um"), 409);
        assertStatus(() -> cancel.cancelPaidByCustomer(id, customer, "preco", ""), 409);
        MP.verify(1, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(order(id)).containsEntry("cancel_note", "");
        Map<String, Object> mine = orders.customerOrder(customer, id).orElseThrow();
        assertThat(mine).containsEntry("status", "cancelado").containsEntry("payment_state", "devolvido");
        assertThat(mine.get("refund").toString()).contains("mode=nenhum");
        assertThat(mine.get("cancel").toString()).contains("by=sistema", "reason=Todo o valor foi devolvido");
    }

    /**
     * Entregue e devolvido por inteiro.
     */
    @Test
    @DisplayName("pedido entregue e devolvido por inteiro continua 'entregue'")
    void deliveredFullRefund() {
        long id = seed("entregue", true, Instant.now());
        ownerRefunds.ownerRefund(id, owner, "100", "Bolo errado, devolvemos tudo");
        assertThat(order(id)).containsEntry("refunded", "100.00").containsEntry("cancelled_at", null);
        assertThat(jdbc.sql("SELECT status FROM orders WHERE shop_order_id = :id").param("id", id).query(String.class)
                .list()).containsOnly("entregue");
    }

    /**
     * Migração 016.
     *
     * @throws Exception se o arquivo não abrir
     */
    @Test
    @DisplayName("migração 016 corrige pedidos antigos: cancelamento depois da devolução total e devolvido aberto")
    void migration016() throws Exception {

        // ── 1. Os três casos.
        long a = seed("producao");
        jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount,
                       approved_amount, decided_at, refunded_at, created_at)
                VALUES (:so, :owner, :customer, 'solicitado', 'concluido', 'desisti', 100, 100,
                        now() - interval '6 minutes', now() - interval '6 minutes', now() - interval '7 minutes')""")
                .param("so", a).param("owner", owner.value()).param("customer", customer.value()).update();
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE shop_order_id = :id").param("id", a).update();
        jdbc.sql("""
                UPDATE shop_orders SET refunded_total = 100, cancelled_at = now(), cancelled_by = 'loja',
                       cancel_reason_code = 'falta_ingrediente', cancel_reason = 'Faltou ingrediente', cancel_note = 'msg'
                 WHERE id = :id""").param("id", a).update();
        long b = seed("producao");
        jdbc.sql("UPDATE shop_orders SET refunded_total = 100 WHERE id = :id").param("id", b).update();
        long c = seed("producao");
        cancel.cancelByOwner(c, owner, "sem_agenda", "");

        // ── 2. Roda a migração e confere.
        jdbc.sql(new String(new ClassPathResource("db/migration/production/V16__016_full_refund_closes.sql")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).update();
        assertThat(order(a)).containsEntry("cancelled_by", "sistema").containsEntry("cancel_reason_code",
                "devolucao_total").containsEntry("cancel_note", "");
        assertThat(order(b)).containsEntry("cancelled_by", "sistema");
        assertThat(order(c)).containsEntry("cancelled_by", "loja").containsEntry("cancel_reason_code", "sem_agenda");
    }

    /**
     * Pagamento depois do cancelamento.
     */
    @Test
    @DisplayName("pagamento depois do cancelamento: devolução automática do sistema e aviso 'tardio'")
    void latePayment() {
        long id = seed("novo", false, null);
        jdbc.sql("""
                UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'cliente', paid_at = now(),
                       paid_mp_id = 'late1', mp_payment_ids = ARRAY['late1'] WHERE id = :id""").param("id", id).update();
        assertThat(cancel.refundLatePayment(id)).isTrue();
        assertThat(refundOf(id)).containsEntry("kind", "sistema").containsEntry("reason_code", "pago_apos_cancelamento")
                .containsEntry("status", "concluido");
        assertThat(log.notices).anyMatch(n -> n instanceof OwnerNotice.Refund r && "tardio".equals(r.kind()));
        assertThat(cancel.refundLatePayment(id)).isFalse();
        assertThat(cancel.refundLatePayment(seed("novo"))).isFalse();
    }

    /**
     * Conciliação.
     */
    @Test
    @DisplayName("devolução feita direto no painel do Mercado Pago entra na conciliação, uma vez")
    void reconcile() {
        long id = seed("producao");
        MP.stubFor(get(urlEqualTo("/v1/payments/" + lastPayment + "/refunds")).willReturn(json(
                "[{\"id\":777001,\"status\":\"approved\",\"amount\":25},{\"id\":777002,\"status\":\"in_process\","
                        + "\"amount\":5}]")));
        cancel.reconcileRefunds(id, lastPayment);
        cancel.reconcileRefunds(id, lastPayment);
        assertThat(order(id)).containsEntry("refunded", "25.00");
        assertThat(refundOf(id)).containsEntry("kind", "loja").containsEntry("status", "concluido")
                .containsEntry("mp_refund_id", "777001");
        MP.stubFor(get(urlEqualTo("/v1/payments/fora/refunds")).willReturn(aResponse().withStatus(500)));
        cancel.reconcileRefunds(id, "fora");
    }

    /**
     * Não pago.
     */
    @Test
    @DisplayName("cancelar pedido não pago segue sem devolução: não há o que devolver")
    void unpaid() {
        long id = seed("novo", false, null);
        Map<String, Object> r = cancel.cancel(id, new CancelRefundUseCases.Who("cliente", null, customer, true),
                "preco", "");
        assertThat(r).containsEntry("refunded", 0L).containsEntry("refundId", null);
        MP.verify(0, postRequestedFor(urlPathMatching("/v1/payments/.*/refunds")));
        assertThat(Files.exists(Path.of("."))).isTrue();
    }
}
