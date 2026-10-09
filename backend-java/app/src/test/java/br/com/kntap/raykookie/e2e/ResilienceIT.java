package br.com.kntap.raykookie.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resiliência (T09) com a aplicação inteira: banco travado, Mercado Pago lento ou fora e Firebase fora.
 */
class ResilienceIT extends AppItSupport {

    /**
     * Banco travado: 503 rápido em rota de negócio, e tudo volta quando o banco volta.
     *
     * @throws InterruptedException se a espera for interrompida
     */
    @Test
    @DisplayName("banco pausado: rota de negócio responde 503 em até 5 s e volta a 200 quando o banco volta")
    void databasePaused() throws InterruptedException {

        // ── 1. Pausa o Postgres, passa da janela em que o Hikari entrega conexão sem testar e chama uma rota.
        String container = postgres().getContainerId();
        postgres().getDockerClient().pauseContainerCmd(container).exec();
        Response response;
        long start;
        try {
            Thread.sleep(Duration.ofMillis(600));
            start = System.nanoTime();
            response = api().get("/api/menu");
        } finally {
            postgres().getDockerClient().unpauseContainerCmd(container).exec();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // ── 2. 503 rápido com a mensagem de indisponível; depois volta ao normal.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("error"))
                .isEqualTo("Serviço indisponível. Tente de novo em instantes.");
        assertThat(elapsedMillis).isLessThanOrEqualTo(5000);
        await().atMost(Duration.ofSeconds(10)).until(() -> api().get("/api/menu").statusCode() == 200);
    }

    /**
     * Mercado Pago lento: o cliente recebe 502 dentro do tempo limite.
     */
    @Test
    @DisplayName("Mercado Pago lento: tentar outro cartão responde 502 em até 11 s, com a mensagem de tentar de novo")
    void slowGateway() {

        // ── 1. Pedido com cartão recusado.
        cardAnswer("FUND", "rejected", "cc_rejected_insufficient_amount", 0);
        cardAnswer("LENTO", "approved", "accredited", 15_000);
        String token = newCustomer().token();
        long order = as(token).body(cardOrder(menuItem(), "FUND")).post("/api/cliente/pedidos").jsonPath()
                .getLong("id");

        // ── 2. Outro cartão com o gateway lento: 502 antes de 11 s.
        long start = System.nanoTime();
        Response slow = as(token).body(card("LENTO")).post("/api/cliente/pedidos/" + order + "/cartao");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThanOrEqualTo(11_000);
        assertThat(slow.statusCode()).isEqualTo(502);
        assertThat(slow.jsonPath().getString("error"))
                .isEqualTo("Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague com Pix.");
    }

    /**
     * Mercado Pago fora na criação do Pix: o pedido fica gravado, sem o código.
     */
    @Test
    @DisplayName("Mercado Pago fora ao gerar o Pix: pedido criado (201) sem o código, pendente de pagamento")
    void gatewayDownOnPix() {
        MP.stubFor(post(urlEqualTo("/v1/payments")).withRequestBody(matchingJsonPath("$.payment_method_id",
                equalTo("pix"))).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        Response created = as(newCustomer().token()).body(order(menuItem(), "pix")).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getMap("pix")).isNull();
        assertThat(jdbc.sql("SELECT paid_at IS NULL FROM shop_orders WHERE id = :id")
                .param("id", created.jsonPath().getLong("id")).query(Boolean.class).single()).isTrue();
    }

    /**
     * Firebase fora: o aviso falha, mas o pedido segue e a dona o vê na tela Pedidos.
     */
    @Test
    @DisplayName("Firebase fora: o push falha e o pedido segue (201, na tela Pedidos da dona)")
    void firebaseDown() {

        // ── 1. A dona tem um aparelho cadastrado.
        String owner = panelSession(OWNER);
        assertThat(as(owner).body(Map.of("token", "fcm-token-de-teste-" + "x".repeat(40))).post("/api/devices")
                .statusCode()).isEqualTo(200);
        FIREBASE.resetRequests();

        // ── 2. Pedido em dinheiro: criado mesmo com o envio do aviso falhando.
        Response created = as(newCustomer().token()).body(order(menuItem(), "dinheiro")).post("/api/cliente/pedidos");
        assertThat(created.statusCode()).isEqualTo(201);
        await().atMost(Duration.ofSeconds(10)).until(() -> !FIREBASE.getAllServeEvents().isEmpty());

        // ── 3. A dona vê o pedido.
        String code = created.jsonPath().getString("code");
        assertThat(as(owner).get("/api/orders").asString()).contains("Pedido online #" + code);
    }
}
