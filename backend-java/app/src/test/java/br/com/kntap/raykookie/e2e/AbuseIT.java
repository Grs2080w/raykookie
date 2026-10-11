package br.com.kntap.raykookie.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Abuso (T07) com a aplicação inteira: X-Forwarded-For forjado, cadastro fechado, APK de gestão, upload gigante e os
 * limites de pedidos e de cartões.
 */
class AbuseIT extends AppItSupport {

    /** Recusa por limite de tentativas de cartão (G04). */
    private static final String CARD_LIMIT = "Muitas tentativas de cartão. Tente pagar com Pix ou fale com a gente.";

    /**
     * Trocar a parte do X-Forwarded-For que o cliente escreve não zera a contagem do login.
     */
    @Test
    @DisplayName("XFF forjado: 8 erros com IPs inventados diferentes barram a 9ª, mesmo com a senha certa")
    void forgedForwardedFor() {
        for (int i = 0; i < 8; i++) {
            assertThat(login("errada", "10.0.0." + i + ", 203.0.113.77").statusCode()).isEqualTo(401);
        }
        Response blocked = login(PASSWORD, "10.9.9.9, 203.0.113.77");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.jsonPath().getString("error"))
                .isEqualTo("Muitas tentativas. Espere alguns minutos e tente de novo.");
    }

    /**
     * Cadastro de conta do painel fechado.
     */
    @Test
    @DisplayName("cadastro fechado: 404 Não encontrado.")
    void registrationClosed() {
        Response response = api().body(Map.of("username", "invasor", "password", "senha-boa-123"))
                .post("/api/auth/register");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Não encontrado.");
    }

    /**
     * O APK de gestão é só da dona (G01).
     */
    @Test
    @DisplayName("APK de gestão: visitante, cliente e outra conta não veem nem baixam; a dona baixa pelo link")
    void managementApk() {

        // ── 1. Ninguém além da dona vê ou baixa o app de gestão.
        String[] tokens = {null, newCustomer().token(), panelSession(STRANGER)};
        for (String token : tokens) {
            assertThat((token == null ? api() : as(token)).get("/api/downloads").jsonPath().getString("server"))
                    .isNull();
            assertThat((token == null ? api() : as(token)).get("/api/downloads/server").statusCode())
                    .isEqualTo(401);
        }
        assertThat(as(panelSession(STRANGER)).post("/api/downloads/server/link").statusCode()).isEqualTo(401);

        // ── 2. A dona gera o link, que baixa sem sessão.
        String url = as(panelSession(OWNER)).post("/api/downloads/server/link").jsonPath().getString("url");
        Response download = api().urlEncodingEnabled(false).get(url);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.asByteArray()).containsExactly(5, 6, 7);
    }

    /**
     * Corpo gigante recusado pelo tamanho declarado, sem ler o corpo.
     *
     * @throws IOException se a conexão falhar
     */
    @Test
    @DisplayName("upload de 13 MB na avaliação e na devolução: 413 sem ler o corpo")
    void hugeUpload() throws IOException {
        String token = newCustomer().token();
        for (String path : new String[] {"/api/cliente/feedback", "/api/cliente/pedidos/1/devolucao"}) {
            try (Socket socket = new Socket("localhost", port)) {
                OutputStream out = socket.getOutputStream();
                out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + token
                        + "\r\nContent-Type: multipart/form-data; boundary=x\r\nContent-Length: 13631488\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String head = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
                assertThat(head).as(path).isEqualTo("HTTP/1.1 413");
            }
        }
    }

    /**
     * Limites de pedidos por cliente (G11).
     */
    @Test
    @DisplayName("G11: com 3 pedidos online sem pagar o próximo é 429; o 11º pedido na hora é 429")
    void orderLimits() {

        // ── 1. Três Pix sem pagar: qualquer pedido novo é barrado.
        pixAnswers();
        String item = menuItem();
        String unpaid = newCustomer().token();
        for (int i = 0; i < 3; i++) {
            assertThat(as(unpaid).body(order(item, "pix")).post("/api/cliente/pedidos").statusCode()).isEqualTo(201);
        }
        assertThat(as(unpaid).body(order(item, "dinheiro")).post("/api/cliente/pedidos").statusCode())
                .isEqualTo(429);

        // ── 2. Em dinheiro passa até 10 na hora; o 11º é barrado.
        String busy = newCustomer().token();
        for (int i = 0; i < 10; i++) {
            assertThat(as(busy).body(order(item, "dinheiro")).post("/api/cliente/pedidos").statusCode())
                    .isEqualTo(201);
        }
        Response blocked = as(busy).body(order(item, "dinheiro")).post("/api/cliente/pedidos");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.jsonPath().getString("error"))
                .isEqualTo("Você fez muitos pedidos em pouco tempo. Tente de novo mais tarde.");
    }

    /**
     * Limite de recusas de cartão por cliente (G04): 20 por dia.
     */
    @Test
    @DisplayName("G04: 20 recusas do mesmo cliente em 24 h: a tentativa seguinte é 429 sem chamar o gateway")
    void cardRefusals() {

        // ── 1. Um pedido recusado; o contador do cliente vai para 19 (como se fossem 19 recusas no dia).
        cardAnswer("FUND", "rejected", "cc_rejected_insufficient_amount", 0);
        cardAnswer("APRO", "approved", "accredited", 0);
        String item = menuItem();
        String token = newCustomer().token();
        long order = as(token).body(cardOrder(item, "FUND")).post("/api/cliente/pedidos").jsonPath().getLong("id");
        long customer = jdbc.sql("SELECT customer_id FROM shop_orders WHERE id = :id").param("id", order)
                .query(Long.class).single();
        jdbc.sql("UPDATE rate_limits SET hits = 19 WHERE key = :key").param("key", "card-fail-customer|" + customer)
                .update();

        // ── 2. A 20ª recusa ainda chega ao gateway; a tentativa seguinte, não.
        assertThat(as(token).body(card("FUND")).post("/api/cliente/pedidos/" + order + "/cartao").statusCode())
                .isEqualTo(200);
        Response blocked = as(token).body(card("APRO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.jsonPath().getString("error")).isEqualTo(CARD_LIMIT);
        MP.verify(2, postRequestedFor(urlEqualTo("/v1/payments")));
    }
}
