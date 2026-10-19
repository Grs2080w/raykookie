package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segurança, categoria 4: o que um cliente esperto tenta. Preço, quantidade, desconto e "pago" são decididos no
 * servidor; o que o cliente manda a mais é ignorado ou recusado.
 */
class SecurityBusinessAbuseIT extends AppItSupport {

    /**
     * Quantidades absurdas nunca viram pedido.
     */
    @Test
    @DisplayName("quantidade negativa, zero, fracionada, texto, gigante ou nula: 4xx e nenhum pedido criado")
    void strangeQuantitiesAreRefused() {
        String item = menuItem();
        String customer = newCustomer().token();
        long before = orders();
        List<Object> quantities = new ArrayList<>(List.of(-1, 0, 1.5, "dois", 1_000_000, 2_147_483_648L,
                Long.MAX_VALUE, "1e9", "NaN"));
        quantities.add(null);
        for (Object qty : quantities) {
            Map<String, Object> body = order(item, "dinheiro");
            Map<String, Object> line = new HashMap<>();
            line.put("id", item);
            line.put("qty", qty);
            body.put("items", List.of(line));
            Response response = as(customer).body(body).post("/api/cliente/pedidos");
            assertThat(response.statusCode()).as("qty=%s", qty).isBetween(400, 499);
        }
        assertThat(orders()).isEqualTo(before);
    }

    /**
     * Item inexistente, repetido e lista vazia.
     */
    @Test
    @DisplayName("itens: id inexistente, lista vazia e id de outro tipo são recusados")
    void invalidItemsAreRefused() {
        String customer = newCustomer().token();
        for (Object items : List.of(List.of(), List.of(Map.of("id", "nao-existe", "qty", 1)),
                List.of(Map.of("id", "' OR 1=1--", "qty", 1)), "texto", Map.of("id", "1", "qty", 1))) {
            Map<String, Object> body = order(menuItem(), "dinheiro");
            body.put("items", items);
            assertThat(as(customer).body(body).post("/api/cliente/pedidos").statusCode()).as("%s", items)
                    .isBetween(400, 499);
        }
    }

    /**
     * Mass assignment: preço, total, desconto, status e dono no corpo do pedido não valem nada.
     */
    @Test
    @DisplayName("mass assignment: price/total/discount/status/paid_at/customer_id no corpo são ignorados; total vem do cardápio")
    void clientCannotSetPriceTotalOrStatus() {
        String item = menuItem(); // R$ 9,50
        Customer victim = newCustomer();
        Customer attacker = newCustomer();
        Map<String, Object> body = order(item, "dinheiro"); // 2 x 9,50
        body.put("items", List.of(Map.of("id", item, "qty", 2, "price", 0.01, "unit_price", 0.01, "name", "grátis")));
        body.putAll(Map.ofEntries(Map.entry("total", 0.01), Map.entry("price", 0.01), Map.entry("discount", 99),
                Map.entry("discount_total", 99), Map.entry("percent", 99), Map.entry("status", "pago"),
                Map.entry("paid", true), Map.entry("paid_at", "2026-01-01T00:00:00Z"),
                Map.entry("customer_id", victim.id()), Map.entry("owner_id", 999999), Map.entry("id", 1)));
        Response created = as(attacker.token()).body(body).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);

        Map<String, Object> row = jdbc.sql("SELECT total, paid_at, customer_id FROM shop_orders ORDER BY id DESC LIMIT 1")
                .query().singleRow();
        assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("19.00");
        assertThat(row.get("paid_at")).as("não nasce pago").isNull();
        assertThat(((Number) row.get("customer_id")).longValue()).as("pedido é de quem tem a sessão")
                .isEqualTo(attacker.id());
    }

    /**
     * Cupom: só o código vale; o percentual é o do cadastro e não empilha.
     */
    @Test
    @DisplayName("cupom: desconto vem do cadastro (15%), não do corpo; cupons empilhados e código forjado não valem")
    void couponIsComputedOnTheServer() {
        String owner = panelSession(OWNER);
        String code = "SEG" + System.nanoTime() % 100000;
        assertThat(as(owner).body(Map.of("code", code, "percent", 15)).post("/api/coupons").statusCode())
                .isEqualTo(201);
        String item = menuItem();
        String customer = newCustomer().token();

        Map<String, Object> body = order(item, "dinheiro");
        body.put("coupon_code", code);
        body.put("discount_percent", 90);
        body.put("discount_total", 18.99);
        body.put("coupon_codes", List.of(code, code));
        Response created = as(customer).body(body).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        BigDecimal total = jdbc.sql("SELECT total FROM shop_orders ORDER BY id DESC LIMIT 1")
                .query(BigDecimal.class).single();
        assertThat(total).as("19,00 - 15% = 16,15").isEqualByComparingTo("16.15");

        for (Object forged : List.of("' OR '1'='1", "SEG0; DROP TABLE coupons", Map.of("a", 1))) {
            Map<String, Object> other = order(item, "dinheiro");
            other.put("coupon_code", forged);
            assertThat(as(customer).body(other).post("/api/cliente/pedidos").statusCode()).as("%s", forged)
                    .isBetween(400, 499);
        }

        // lista com o mesmo código vira o mesmo código: um desconto só, nunca empilhado
        Map<String, Object> listed = order(item, "dinheiro");
        listed.put("coupon_code", List.of(code, code));
        assertThat(as(customer).body(listed).post("/api/cliente/pedidos").statusCode()).isIn(201, 400);
        assertThat(jdbc.sql("SELECT max(total) FROM shop_orders WHERE total < 19").query(BigDecimal.class).single())
                .as("nenhum pedido ficou abaixo de 16,15").isEqualByComparingTo("16.15");
    }

    /**
     * Sem pagar o Pix o pedido não vira "pago": não há rota do cliente que marque, e o webhook sem assinatura não conta.
     */
    @Test
    @DisplayName("pago sem pagar: PATCH/PUT/POST de status pelo cliente e webhook sem assinatura não pagam o pedido")
    void orderCannotBeMarkedPaidWithoutPaying() {
        pixAnswers();
        String item = menuItem();
        Customer customer = newCustomer();
        Response created = as(customer.token()).body(order(item, "pix")).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = created.jsonPath().getString("id");

        Map<String, Object> paid = Map.of("status", "pago", "paid", true, "paid_at", "2026-01-01T00:00:00Z");
        for (String path : List.of("/api/cliente/pedidos/" + id, "/api/cliente/pedidos/" + id + "/pago",
                "/api/cliente/pedidos/" + id + "/confirmar", "/api/cliente/pedidos/" + id + "/pagar")) {
            for (String method : List.of("PATCH", "PUT", "POST")) {
                Response response = as(customer.token()).body(paid).request(method, path);
                assertThat(response.statusCode()).as("%s %s", method, path).isBetween(400, 499);
            }
        }
        // webhook sem assinatura e com assinatura inventada: recusado, o pedido segue pendente
        Map<String, Object> notice = Map.of("type", "payment", "data", Map.of("id", "123456789"));
        assertThat(api().body(notice).post("/api/webhooks/mercadopago").statusCode()).isEqualTo(401);
        assertThat(api().header("x-signature", "ts=1,v1=00").header("x-request-id", "x").body(notice)
                .post("/api/webhooks/mercadopago").statusCode()).isEqualTo(401);

        assertThat(jdbc.sql("SELECT paid_at IS NULL FROM shop_orders WHERE id = :id").param("id", Long.parseLong(id))
                .query(Boolean.class).single()).as("segue sem pagar").isTrue();
    }

    /**
     * Estoque e receitas só pela dona: o cliente não altera nada de gestão.
     */
    @Test
    @DisplayName("gestão por cliente: criar/alterar ingrediente, receita ou cupom com sessão de cliente é negado")
    void customerCannotWriteManagementData() {
        String customer = newCustomer().token();
        String item = menuItem();
        assertThat(as(customer).body(Map.of("name", "Farinha", "unit", "g")).post("/api/ingredients").statusCode())
                .isIn(401, 403);
        assertThat(as(customer).body(Map.of("sale_price", 0.01)).patch("/api/recipes/" + item + "/cardapio")
                .statusCode()).isIn(401, 403, 404);
        assertThat(as(customer).body(Map.of("code", "GRATIS", "percent", 90)).post("/api/coupons").statusCode())
                .isIn(401, 403);
        assertThat(as(customer).delete("/api/recipes/" + item).statusCode()).isIn(401, 403);
    }

    private long orders() {
        return jdbc.sql("SELECT count(*) FROM shop_orders").query(Long.class).single();
    }
}
