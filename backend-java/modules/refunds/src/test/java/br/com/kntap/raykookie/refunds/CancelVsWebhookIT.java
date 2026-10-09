package br.com.kntap.raykookie.refunds;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.application.ConfirmPaymentUseCase;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cancelamento do cliente e confirmação do pagamento do mesmo pedido ao mesmo tempo (T06): o estado final é sempre
 * coerente — cancelado com devolução registrada, ou pago e não cancelado; nunca cancelado e pago sem devolução.
 */
class CancelVsWebhookIT extends RefundsItSupport {

    /** Rodadas. */
    private static final int ROUNDS = 12;

    /** Pedidos (cancelamento pelo cliente). */
    @Autowired
    OrdersApi orders;

    /** Confirmação única. */
    @Autowired
    ConfirmPaymentUseCase confirm;

    /**
     * Corrida.
     *
     * @throws Exception se uma tarefa falhar de um jeito inesperado
     */
    @Test
    @DisplayName("cancelar e confirmar ao mesmo tempo, 12 vezes: sempre (cancelado + devolução) ou (pago e aberto)")
    void race() throws Exception {
        MP.stubFor(get(urlPathMatching("/v1/payments/[^/]+")).willReturn(json("{\"id\":1,\"status\":\"pending\"}")));
        MP.stubFor(put(urlPathMatching("/v1/payments/[^/]+")).willReturn(json("{}")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                race(pool);
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Uma rodada.
     *
     * @param pool threads
     * @throws Exception se uma tarefa falhar de um jeito inesperado
     */
    private void race(ExecutorService pool) throws Exception {

        // ── 1. Pedido com Pix gerado e ainda não pago.
        long id = seed("novo", false, null);
        String pix = "pix-" + id;
        jdbc.sql("UPDATE shop_orders SET mp_payment_id = :p, mp_payment_ids = ARRAY[CAST(:p AS text)] WHERE id = :id")
                .param("p", pix).param("id", id).update();

        // ── 2. Cancelamento e confirmação soltos juntos.
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> cancel = () -> {
            go.await();
            return orders.cancelByCustomer(id, customer, Map.of("reason_code", "preco"));
        };
        Callable<Object> paid = () -> {
            go.await();
            return confirm.confirm(id, new GatewayPayment(pix, "approved", "", String.valueOf(id),
                    Money.ofCents(10_000), Money.ZERO, null, null), null, "pix");
        };
        List<Future<Object>> both = List.of(pool.submit(cancel), pool.submit(paid));
        go.countDown();
        for (Future<Object> f : both) {
            f.get();
        }

        // ── 3. Estado coerente.
        Map<String, Object> o = jdbc.sql("""
                SELECT paid_at IS NOT NULL AS paid, cancelled_at IS NOT NULL AS cancelled,
                       (SELECT count(*) FROM refunds r WHERE r.shop_order_id = so.id) AS refunds
                  FROM shop_orders so WHERE id = :id""").param("id", id).query().singleRow();
        boolean isPaid = (Boolean) o.get("paid");
        boolean isCancelled = (Boolean) o.get("cancelled");
        long refunds = ((Number) o.get("refunds")).longValue();
        assertThat(isPaid && isCancelled && refunds == 0).as("pedido %d cancelado e pago sem devolução", id).isFalse();
        assertThat(isPaid || isCancelled).isTrue();
    }
}
