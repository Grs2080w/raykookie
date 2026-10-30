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
     * Pedido à mão com vários itens: uma linha por item no mesmo grupo.
     */
    @Test
    @DisplayName("pedido à mão com vários itens: uma linha por item; troca rápida e exclusão valem para o pedido todo")
    void multiItemOrder() {

        // ── 1. Cria com dois itens: duas linhas no mesmo grupo.
        Map<String, Object> body = Map.of("customer", "Ana Multi", "items", List.of(
                Map.of("product", "Bolo", "qty", "1", "unit_price", "50"),
                Map.of("product", "Brigadeiro", "qty", "10", "unit_price", "3,50")));
        Response created = as(owner, body).post("/api/orders");
        assertThat(created.statusCode()).isEqualTo(201);
        String group = created.jsonPath().getString("group_id");
        long first = created.jsonPath().getLong("id");
        assertThat(group).isNotBlank();
        assertThat(jdbc.sql("SELECT product FROM orders WHERE group_id = CAST(:g AS uuid) ORDER BY id")
                .param("g", group).query(String.class).list()).containsExactly("Bolo", "Brigadeiro");

        // ── 2. A troca rápida de uma linha vale para as duas.
        assertThat(as(owner, Map.of("payment", "pago")).patch("/api/orders/" + first).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE group_id = CAST(:g AS uuid) AND payment = 'pago'")
                .param("g", group).query(Integer.class).single()).isEqualTo(2);

        // ── 3. Editar com um item só tira o outro e solta o grupo.
        Map<String, Object> one = Map.of("customer", "Ana Multi", "items", List.of(
                Map.of("id", first, "product", "Bolo", "qty", "2", "unit_price", "50")));
        assertThat(as(owner, one).patch("/api/orders/" + first).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE customer = 'Ana Multi'").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT group_id IS NULL FROM orders WHERE id = :id").param("id", first)
                .query(Boolean.class).single()).isTrue();

        // ── 4. Item novo volta a formar grupo; excluir leva o pedido todo.
        Map<String, Object> two = Map.of("customer", "Ana Multi", "items", List.of(
                Map.of("id", first, "product", "Bolo", "qty", "2", "unit_price", "50"),
                Map.of("product", "Docinho", "qty", "5", "unit_price", "4")));
        assertThat(as(owner, two).patch("/api/orders/" + first).statusCode()).isEqualTo(200);
        assertThat(as(owner, Map.of()).delete("/api/orders/" + first).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE customer = 'Ana Multi'").query(Integer.class).single())
                .isZero();
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
