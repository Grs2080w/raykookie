package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pedidos pela rede, com o Postgres real: criação, lista do cliente, cancelamento, limites (G11) e avisos.
 */
class OrdersWebIT extends OrdersItSupport {

    /**
     * Pix: a porta de pagamentos gera o código e o pedido aparece para o cliente.
     */
    @Test
    @DisplayName("pedido com Pix: 201 com o código da porta de pagamentos; aviso à dona; aparece na lista")
    void pixOrder() {

        // ── 1. Cria.
        Response created = create("pix");
        assertThat(created.statusCode()).isEqualTo(201);
        long id = created.jsonPath().getLong("id");
        assertThat(created.jsonPath().getString("pix.code")).isEqualTo("pix-" + id);
        assertThat(created.jsonPath().getString("card")).isNull();
        assertThat(fakes.pix).anyMatch(order -> order.id() == id && order.total().cents() == 1900
                && order.email().startsWith("ped-"));
        assertThat(fakes.notices).anyMatch(n -> n instanceof OwnerNotice.NewOrder o && o.orderId() == id);

        // ── 2. Na tela Pedidos e na lista do cliente.
        assertThat(jdbc.sql("SELECT notes FROM orders WHERE shop_order_id = :id").param("id", id)
                .query(String.class).single()).contains("Pagamento: Pix").contains("Tel (11) 91234-5678");
        Response list = api().header("Authorization", "Bearer " + customer).get("/api/cliente/pedidos");
        assertThat(list.jsonPath().getString("find { it.id == " + id + " }.payment_state")).isEqualTo("pendente");
        assertThat(ordersApi.customerOrder(customerOf(id), id)).get().extracting(o -> o.get("total")).isEqualTo(19L);
    }

    /**
     * Mudança de situação pela dona avisa o cliente.
     */
    @Test
    @DisplayName("dona muda a linha para produção: o cliente é avisado e o cancelamento vira 409")
    void statusChangeNotifiesCustomer() {
        long id = create("dinheiro").jsonPath().getLong("id");
        long line = jdbc.sql("SELECT id FROM orders WHERE shop_order_id = :id").param("id", id).query(Long.class)
                .single();
        assertThat(as(owner, Map.of("status", "producao")).patch("/api/orders/" + line).statusCode()).isEqualTo(200);
        assertThat(fakes.notices).anyMatch(n -> n instanceof CustomerNotice.Status s && s.order().id() == id
                && "producao".equals(s.status()));
        Response refused = as(customer, Map.of()).post("/api/cliente/pedidos/" + id + "/cancelar");
        assertThat(refused.statusCode()).isEqualTo(409);
    }

    /**
     * Cancelamento sem pagamento e com Pix pago agora.
     */
    @Test
    @DisplayName("cancelar: sem pagamento cancela e registra o histórico; Pix pago agora segue pela devolução")
    void cancel() {

        // ── 1. Sem pagamento.
        long id = create("dinheiro").jsonPath().getLong("id");
        Response cancelled = as(customer, Map.of("reason_code", "outro", "reason", "mudei de ideia"))
                .post("/api/cliente/pedidos/" + id + "/cancelar");
        assertThat(cancelled.jsonPath().getString("state")).isEqualTo("cancelled");
        assertThat(ordersApi.customerOrders(customerOf(id)))
                .anyMatch(o -> ((Number) o.get("id")).longValue() == id && "cancelado".equals(o.get("status")));
        assertThat(jdbc.sql("SELECT type FROM order_events WHERE shop_order_id = :id").param("id", id)
                .query(String.class).list()).containsExactly("cancelado");
        assertThat(fakes.notices).anyMatch(n -> n instanceof OwnerNotice.Cancelled c && c.orderId() == id
                && "mudei de ideia".equals(c.reason()));

        // ── 2. Pix que acabou de ser pago: devolução.
        long pix = create("pix").jsonPath().getLong("id");
        jdbc.sql("UPDATE shop_orders SET mp_payment_id = '123' WHERE id = :id").param("id", pix).update();
        fakes.settled = true;
        Response refunded = as(customer, Map.of()).post("/api/cliente/pedidos/" + pix + "/cancelar");
        assertThat(refunded.jsonPath().getDouble("refunded")).isEqualTo(9.5);
        assertThat(fakes.refunded).contains(pix);

        // ── 3. Cancelamento pela dona vai para a devolução.
        assertThat(as(owner, Map.of("code", "sem_estoque")).post("/api/orders/shop/" + id + "/cancelar")
                .jsonPath().getBoolean("ok")).isTrue();
        assertThat(as(customer, Map.of()).post("/api/cliente/pedidos/abc/cancelar").statusCode()).isEqualTo(404);
    }

    /**
     * Limites por cliente (G11).
     */
    @Test
    @DisplayName("G11: 4º pedido online sem pagar é 429 (só conta as últimas 24 h); 11º pedido na hora é 429")
    void limits() {

        // ── 1. Três Pix em aberto: o quarto é recusado.
        for (int i = 0; i < 3; i++) {
            assertThat(create("pix").statusCode()).isEqualTo(201);
        }
        Response fourth = create("pix");
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.jsonPath().getString("error"))
                .isEqualTo("Você fez muitos pedidos em pouco tempo. Tente de novo mais tarde.");

        // ── 2. Com mais de 24 horas, os Pix em aberto não travam mais o cliente.
        jdbc.sql("UPDATE shop_orders SET created_at = now() - interval '2 days' WHERE paid_at IS NULL").update();
        assertThat(create("pix").statusCode()).isEqualTo(201);

        // ── 3. Pagos, saem da conta; o limite por hora vale para qualquer forma de pagamento.
        jdbc.sql("UPDATE shop_orders SET paid_at = now() WHERE payment_method = 'pix' AND paid_at IS NULL").update();
        for (int i = 0; i < 6; i++) {
            assertThat(create("dinheiro").statusCode()).isEqualTo(201);
        }
        assertThat(create("dinheiro").statusCode()).isEqualTo(429);
    }
}
