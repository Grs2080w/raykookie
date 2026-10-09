package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.api.ShopOrderLocking;
import io.restassured.path.json.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detalhes da lista do cliente (devoluções, Pix, cancelamento) e a trava de pagamento que pagamentos usa.
 */
class OrderDetailsIT extends OrdersItSupport {

    /** Trava de pagamento. */
    @Autowired
    ShopOrderLocking locking;

    /** Transações. */
    @Autowired
    TransactionTemplate transactions;

    /**
     * O pedido na lista do cliente.
     *
     * @param id pedido
     * @return o JSON do pedido
     */
    private JsonPath view(long id) {
        return new JsonPath(api().header("Authorization", "Bearer " + customer).get("/api/cliente/pedidos").asString())
                .setRootPath("find { it.id == " + id + " }");
    }

    /**
     * Pedido pago com devoluções.
     */
    @Test
    @DisplayName("pedido pago com devoluções: estado, rótulos, falha vista como processando, solicitação existente")
    void paidWithRefunds() {

        // ── 1. Pago, com uma devolução que falhou e uma solicitação negada.
        long id = create("pix").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET paid_at = now(), paid_mp_id = '9', refunded_total = 5 WHERE id = :id")
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount,
                                     approved_amount, decision_code)
                SELECT id, owner_id, customer_id, 'loja', 'falhou', 'outro', 4, 4, '' FROM shop_orders WHERE id = :id
                """).param("id", id).update();
        jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, reason_code, requested_amount,
                                     decision_code)
                SELECT id, owner_id, customer_id, 'solicitado', 'negado', 'nao_recebi', 10, 'outro'
                  FROM shop_orders WHERE id = :id""").param("id", id).update();

        // ── 2. O que o cliente vê.
        JsonPath json = view(id);
        assertThat(json.getString("payment_state")).isEqualTo("devolucao_parcial");
        assertThat(json.getBoolean("paid")).isTrue();
        assertThat(json.getString("pix")).isNull();
        assertThat(json.getList("refunds.status")).containsExactlyInAnyOrder("processando", "negado");
        assertThat(json.getList("refunds.decision_reason")).contains("Outro motivo", "");
        assertThat(json.getString("refund.mode")).isEqualTo("nenhum");
        assertThat(json.getString("refund.why_not"))
                .isEqualTo("Já existe uma solicitação de devolução para este pedido.");
    }

    /**
     * Pix vencido e pedido cancelado.
     */
    @Test
    @DisplayName("Pix vencido vai sem o código; pedido cancelado traz quem cancelou e o motivo")
    void expiredPixAndCancelled() {

        // ── 1. Pix vencido.
        long pix = create("pix").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET pix_qr_code = 'abc', pix_qr_base64 = 'png', pix_expires_at = now() - "
                + "interval '1 minute' WHERE id = :id").param("id", pix).update();
        JsonPath json = view(pix);
        assertThat(json.getBoolean("pix.expired")).isTrue();
        assertThat(json.getString("pix.code")).isEmpty();
        assertThat(json.getString("payment_state")).isEqualTo("expirado");

        // ── 2. Cancelado pela loja.
        jdbc.sql("UPDATE shop_orders SET cancelled_at = now(), cancelled_by = 'loja', cancel_reason = 'Sem estoque' "
                + "WHERE id = :id").param("id", pix).update();
        jdbc.sql("UPDATE orders SET status = 'cancelado' WHERE shop_order_id = :id").param("id", pix).update();
        json = view(pix);
        assertThat(json.getString("cancel.by")).isEqualTo("loja");
        assertThat(json.getString("cancel.reason")).isEqualTo("Sem estoque");
        assertThat(json.getString("pix")).isNull();
        assertThat(json.getString("status")).isEqualTo("cancelado");
    }

    /**
     * Trava de pagamento.
     */
    @Test
    @DisplayName("ShopOrderLocking: lê com trava, confere o pagamento e marca pago (linhas viram 'pago')")
    void locking() {

        // ── 1. Lê com trava.
        long id = create("pix").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET mp_payment_ids = ARRAY['111'] WHERE id = :id").param("id", id).update();
        ShopOrderLocking.PayableOrder order = transactions.execute(status -> locking.lockById(id).orElseThrow());
        assertThat(order.ownsPayment("111")).isTrue();
        assertThat(order.ownsPayment("222")).isFalse();
        assertThat(order.paid()).isFalse();
        assertThat(order.total().cents()).isEqualTo(1900);

        // ── 2. Marca pago com o cartão.
        transactions.executeWithoutResult(status -> locking.markPaid(id, "222", 3));
        Map<String, Object> row = jdbc.sql("SELECT paid_mp_id, installments, mp_payment_ids::text AS ids FROM "
                + "shop_orders WHERE id = :id").param("id", id).query().singleRow();
        assertThat(row).containsEntry("paid_mp_id", "222").containsEntry("ids", "{111,222}");
        assertThat(((Number) row.get("installments")).intValue()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT payment FROM orders WHERE shop_order_id = :id").param("id", id).query(String.class)
                .single()).isEqualTo("pago");
        ShopOrderLocking.PayableOrder paid = transactions.execute(status -> locking.lockById(id).orElseThrow());
        assertThat(paid.paidWith("222")).isTrue();
        assertThat(paid.paidWith("111")).isFalse();
        assertThat(ordersApi.newPublicCode()).isBetween(1000, 99_999);
    }

    /**
     * O que pedidos oferece aos outros módulos.
     */
    @Test
    @DisplayName("OrdersApi: histórico, entrega e cancelamento pelo cliente")
    void ordersApi() {
        long id = create("dinheiro").jsonPath().getLong("id");
        ordersApi.logEvent(id, "sistema", "teste", Map.of("x", 1));
        assertThat(ordersApi.events(id)).extracting(e -> e.get("type")).containsExactly("teste");
        jdbc.sql("UPDATE orders SET status = 'entregue' WHERE shop_order_id = :id").param("id", id).update();
        ordersApi.syncDelivered(id);
        assertThat(jdbc.sql("SELECT delivered_at IS NOT NULL FROM shop_orders WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
        long other = create("dinheiro").jsonPath().getLong("id");
        assertThat(ordersApi.cancelByCustomer(other, customerOf(other), Map.of("reason_code", "preco")))
                .containsEntry("state", "cancelled");
        long owner = jdbc.sql("SELECT owner_id FROM shop_orders WHERE id = :id").param("id", id).query(Long.class)
                .single();
        assertThat(ordersApi.ownerOrders(new OwnerId(owner))).isNotEmpty();
    }
}
