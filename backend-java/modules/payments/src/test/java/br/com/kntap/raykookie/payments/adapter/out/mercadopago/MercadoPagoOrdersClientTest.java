package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adaptador da API Orders contra um Mercado Pago falso. As respostas são as reais do sandbox (12/10/2026): pedido de
 * cartão aprovado, Pix aguardando, desafio 3DS, recusa em HTTP 402, devolução parcial e total.
 */
class MercadoPagoOrdersClientTest {

    /** Mercado Pago falso. */
    private static final WireMockServer MP = new WireMockServer(wireMockConfig().dynamicPort());

    /** Tolerância de relógio do webhook (não usada aqui). */
    private static final Duration SKEW = Duration.ofMinutes(10);

    /** Adaptador sob teste, com 3DS ligado. */
    private MercadoPagoOrdersClient client;

    /** Roteador entre as duas APIs. */
    private RoutingPaymentGateway router;

    /** Sobe o servidor falso. */
    @BeforeAll
    static void start() {
        MP.start();
    }

    /** Desliga o servidor falso. */
    @AfterAll
    static void stop() {
        MP.stop();
    }

    /** Zera os desvios e monta os adaptadores apontando para o servidor falso. */
    @BeforeEach
    void setUp() {
        MP.resetAll();
        PaymentSettings settings = new PaymentSettings("APP_USR-test", "s", SKEW, 60, "https://loja.teste", "");
        String base = "http://localhost:" + MP.port();
        MercadoPagoHttp http = new MercadoPagoHttp(base, settings);
        client = new MercadoPagoOrdersClient(http, "on_fraud_risk");
        router = new RoutingPaymentGateway(new MercadoPagoClient(base, settings), client, "orders");
    }

    /**
     * Resposta JSON.
     *
     * @param status HTTP
     * @param body   corpo
     * @return a resposta
     */
    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    /**
     * Cobrança de cartão de exemplo.
     *
     * @param methodId bandeira como o BIN devolve ({@code master}, {@code debelo}…)
     * @param typeId   tipo informado pelo cliente (nulo = deduzir)
     * @return a cobrança
     */
    private static PaymentGateway.CardCharge card(String methodId, String typeId) {
        return new PaymentGateway.CardCharge("36", "key-1", Money.ofCents(1000), "Pedido #21212 - Raykookie", "tok",
                1, methodId, null, "ana@teste.com", "Ana", "52998224725", new PaymentGateway.Buyer("Souza",
                        "(11) 91234-5678", "dev-1", "203.0.113.9"), typeId);
    }

    /**
     * Pix aguardando: monta o pedido com uma hora de validade, valores em texto e o QR devolvido.
     */
    @Test
    @DisplayName("Pix: pedido online automático, valores em texto, validade em minutos; volta pendente com o QR")
    void pix() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01PIX","external_reference":"36","total_amount":"6.40","status":"action_required",
                 "status_detail":"waiting_transfer","transactions":{"payments":[{"id":"PAY1","amount":"6.40",
                 "status":"action_required","status_detail":"waiting_transfer","payment_method":{"id":"pix",
                 "type":"bank_transfer","qr_code":"000201","qr_code_base64":"iVBOR"}}]}}""")));

        GatewayPayment pix = client.createPix(new PaymentGateway.PixCharge("36", "k-pix", Money.ofCents(640),
                "Pedido #21212", "ana@teste.com", "Ana", Instant.now().plus(Duration.ofHours(24)).plusSeconds(30)));

        assertThat(pix.id()).isEqualTo("ORDTST01PIX");
        assertThat(pix.status()).isEqualTo("pending");
        assertThat(pix.open()).isTrue();
        assertThat(pix.amount()).isEqualTo(Money.ofCents(640));
        assertThat(pix.qrCode()).isEqualTo("000201");
        assertThat(pix.qrBase64()).isEqualTo("iVBOR");
        assertThat(pix.externalReference()).isEqualTo("36");
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders")).withHeader("X-Idempotency-Key", equalTo("k-pix"))
                .withHeader("Authorization", equalTo("Bearer APP_USR-test"))
                .withRequestBody(matching(".*\"total_amount\":\"6.40\".*"))
                .withRequestBody(matching(".*\"expiration_time\":\"PT24H\".*"))
                .withRequestBody(containing("\"id\":\"pix\"")).withRequestBody(containing("bank_transfer")));
    }

    /**
     * A data de cadastro do cliente segue em {@code additional_info.payer.registration_date}.
     */
    @Test
    @DisplayName("cartão: a data de cadastro vai em additional_info como chave pontuada (a API Orders recusa o aninhado)")
    void registrationDate() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST02","external_reference":"36","total_amount":"10.00","status":"processed",
                 "transactions":{"payments":[{"id":"PAY3","amount":"10.00","status":"processed",
                 "status_detail":"accredited","payment_method":{"id":"master","type":"credit_card"}}]}}""")));
        PaymentGateway.CardCharge base = card("master", null);
        PaymentGateway.Buyer buyer = new PaymentGateway.Buyer("Souza", null, "dev-1", null,
                Instant.parse("2026-03-04T10:15:30Z"));

        client.createCard(new PaymentGateway.CardCharge(base.reference(), base.idempotencyKey(), base.amount(),
                base.description(), base.token(), base.installments(), base.methodId(), null, base.email(),
                base.firstName(), base.cpf(), buyer, null));

        MP.verify(postRequestedFor(urlEqualTo("/v1/orders")).withRequestBody(equalToJson("""
                {"additional_info":{"payer.registration_date":"2026-03-04T10:15:30Z"}}""", true, true)));
    }

    /**
     * Cartão de crédito aprovado, com o 3DS ligado no pedido.
     */
    @Test
    @DisplayName("crédito: parcelas, token, descrição na fatura e 3DS no pedido; aprovado volta com o id do pedido")
    void credit() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01CARD","external_reference":"36","total_amount":"10.00","status":"processed",
                 "status_detail":"accredited","transactions":{"payments":[{"id":"PAY2","amount":"10.00",
                 "paid_amount":"10.00","status":"processed","status_detail":"accredited",
                 "payment_method":{"id":"master","type":"credit_card","installments":1}}]}}""")));

        GatewayPayment pay = client.createCard(card("master", null));

        assertThat(pay.approved()).isTrue();
        assertThat(pay.id()).isEqualTo("ORDTST01CARD");
        assertThat(pay.amount()).isEqualTo(Money.ofCents(1000));
        assertThat(pay.challengePending()).isFalse();
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders")).withHeader("X-meli-session-id", equalTo("dev-1"))
                .withRequestBody(equalToJson("""
                        {"type":"online","processing_mode":"automatic","total_amount":"10.00",
                         "external_reference":"36","description":"Pedido #21212 - Raykookie",
                         "items":[{"title":"Pedido #21212 - Raykookie","unit_price":"10.00","quantity":1,
                                   "category_id":"food"}],
                         "payer":{"email":"ana@teste.com","first_name":"Ana","last_name":"Souza",
                                  "identification":{"type":"CPF","number":"52998224725"},
                                  "phone":{"area_code":"11","number":"912345678"}},
                         "config":{"online":{"transaction_security":{"validation":"on_fraud_risk",
                                   "liability_shift":"required"}}},
                         "transactions":{"payments":[{"amount":"10.00","payment_method":{"id":"master",
                                   "type":"credit_card","token":"tok","statement_descriptor":"RAYKOOKIE",
                                   "installments":1}}]}}""")));
    }

    /**
     * Débito e pré-pago não levam parcelas; o id do débito segue como o BIN devolve ("debelo").
     */
    @Test
    @DisplayName("débito (id debelo, como o BIN devolve) e pré-pago (elo): sem parcelas, tipo certo")
    void debitAndPrepaid() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01DEB","total_amount":"10.00","status":"processed","status_detail":"accredited",
                 "transactions":{"payments":[{"id":"PAY3","amount":"10.00","status":"processed",
                 "status_detail":"accredited"}]}}""")));

        assertThat(client.createCard(card("debelo", null)).approved()).isTrue();
        assertThat(client.createCard(card("elo", "prepaid_card")).approved()).isTrue();

        MP.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withRequestBody(containing("\"id\":\"debelo\",\"type\":\"debit_card\""))
                .withRequestBody(notMatching(".*installments.*")));
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withRequestBody(containing("\"id\":\"elo\",\"type\":\"prepaid_card\""))
                .withRequestBody(notMatching(".*installments.*")));
    }

    /**
     * Desafio 3DS: o pedido fica "action_required / pending_challenge" com o endereço do desafio.
     */
    @Test
    @DisplayName("3DS: pending_challenge vira pagamento pendente com o endereço do desafio")
    void threeDsChallenge() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01TDS","total_amount":"10.00","status":"action_required",
                 "status_detail":"pending_challenge","transactions":{"payments":[{"id":"PAY4","amount":"10.00",
                 "status":"action_required","status_detail":"pending_challenge","payment_method":{"id":"visa",
                 "type":"credit_card","transaction_security":{"id":"01a1",
                 "url":"https://www.mercadopago.com.br/auth/card/validation/pages/remedies/01a1",
                 "validation":"on_fraud_risk","liability_shift":"required"}}}]}}""")));

        GatewayPayment pay = client.createCard(card("visa", null));

        assertThat(pay.status()).isEqualTo("pending");
        assertThat(pay.open()).isTrue();
        assertThat(pay.challengePending()).isTrue();
        assertThat(pay.challengeUrl()).isEqualTo("https://www.mercadopago.com.br/auth/card/validation/pages/remedies/01a1");
    }

    /**
     * Com {@code MP_3DS=never} o pedido não pede desafio.
     */
    @Test
    @DisplayName("MP_3DS=never: o pedido não leva a configuração de 3DS; valor inválido não sobe")
    void threeDsOff() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01N","total_amount":"10.00","status":"processed","status_detail":"accredited",
                 "transactions":{"payments":[{"id":"PAY5","amount":"10.00","status":"processed",
                 "status_detail":"accredited"}]}}""")));
        PaymentSettings settings = new PaymentSettings("APP_USR-test", "s", SKEW, 60, "https://loja.teste", "");

        new MercadoPagoOrdersClient(new MercadoPagoHttp("http://localhost:" + MP.port(), settings), "never")
                .createCard(card("master", null));

        MP.verify(postRequestedFor(urlEqualTo("/v1/orders")).withRequestBody(notMatching(".*transaction_security.*")));
        assertThatThrownBy(() -> new MercadoPagoOrdersClient(new MercadoPagoHttp("http://x", settings), "sempre"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MP_3DS");
    }

    /**
     * Recusa: o Mercado Pago responde 402 com o pedido em "data".
     */
    @Test
    @DisplayName("recusa (HTTP 402): vira pagamento recusado com o código cc_rejected_*, sem exceção")
    void refusal() {
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(402, """
                {"errors":[{"code":"failed","message":"The following transactions failed",
                 "details":["PAY6: insufficient_amount"]}],
                 "data":{"id":"ORDTST01REF","total_amount":"10.00","status":"failed","status_detail":"failed",
                 "transactions":{"payments":[{"id":"PAY6","amount":"10.00","status":"failed",
                 "status_detail":"insufficient_amount"}]}}}""")));

        GatewayPayment pay = client.createCard(card("master", null));

        assertThat(pay.status()).isEqualTo("rejected");
        assertThat(pay.approved()).isFalse();
        assertThat(pay.open()).isFalse();
        assertThat(pay.statusDetail()).isEqualTo("cc_rejected_insufficient_amount");
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(400, """
                {"errors":[{"code":"property_value","message":"x"}]}""")));
        assertThatThrownBy(() -> client.createCard(card("master", null))).isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(400));
    }

    /**
     * Tradução de status e motivos.
     */
    @Test
    @DisplayName("status do pedido → status do sistema; motivo desconhecido segue como veio")
    void statusMapping() {
        assertThat(MercadoPagoOrdersClient.status("processed")).isEqualTo("approved");
        assertThat(MercadoPagoOrdersClient.status("action_required")).isEqualTo("pending");
        assertThat(MercadoPagoOrdersClient.status("processing")).isEqualTo("in_process");
        assertThat(MercadoPagoOrdersClient.status("created")).isEqualTo("in_process");
        assertThat(MercadoPagoOrdersClient.status("in_review")).isEqualTo("in_process");
        assertThat(MercadoPagoOrdersClient.status("failed")).isEqualTo("rejected");
        assertThat(MercadoPagoOrdersClient.status("canceled")).isEqualTo("cancelled");
        assertThat(MercadoPagoOrdersClient.status("expired")).isEqualTo("cancelled");
        assertThat(MercadoPagoOrdersClient.status("refunded")).isEqualTo("refunded");
        assertThat(MercadoPagoOrdersClient.status("charged_back")).isEqualTo("charged_back");
        assertThat(MercadoPagoOrdersClient.status("algo_novo")).isEqualTo("in_process");
        assertThat(MercadoPagoOrdersClient.reason("high_risk")).isEqualTo("cc_rejected_high_risk");
        assertThat(MercadoPagoOrdersClient.reason("3ds_challenge_expired")).isEqualTo("cc_rejected_3ds_challenge");
        assertThat(MercadoPagoOrdersClient.reason("novo_motivo")).isEqualTo("novo_motivo");
    }

    /**
     * Consulta, cancelamento e devoluções (total e parcial).
     */
    @Test
    @DisplayName("consultar, cancelar, devolver tudo (sem corpo) e devolver parte (com o pagamento e o valor)")
    void getCancelRefund() {
        String order = """
                {"id":"ORDTST01OK","external_reference":"36","total_amount":"10.00","status":"processed",
                 "status_detail":"partially_refunded","transactions":{"payments":[{"id":"PAY7","amount":"10.00",
                 "status":"processed","status_detail":"partially_refunded"}],
                 "refunds":[{"id":"REF1","transaction_id":"PAY7","amount":"3.00","status":"processed"}]}}""";
        MP.stubFor(get(urlEqualTo("/v1/orders/ORDTST01OK")).willReturn(json(200, order)));
        MP.stubFor(post(urlEqualTo("/v1/orders/ORDTST01OK/cancel")).willReturn(json(200, "{}")));
        MP.stubFor(post(urlEqualTo("/v1/orders/ORDTST01OK/refund")).willReturn(json(201, order)));

        GatewayPayment got = client.getPayment("ORDTST01OK");
        assertThat(got.approved()).isTrue();
        assertThat(got.refundedAmount()).isEqualTo(Money.ofCents(300));
        assertThat(got.refundedOrChargedBack()).isTrue();

        client.cancel("ORDTST01OK");
        // a API Orders recusa o cancelamento sem X-Idempotency-Key (400 empty_required_header)
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders/ORDTST01OK/cancel")).withHeader("X-Idempotency-Key",
                equalTo("rayka-cancel-ORDTST01OK")));

        GatewayRefund part = client.refund("ORDTST01OK", Money.ofCents(300), "idem-1");
        assertThat(part.id()).isEqualTo("REF1");
        assertThat(part.status()).isEqualTo("approved");
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders/ORDTST01OK/refund")).withHeader("X-Idempotency-Key",
                equalTo("idem-1")).withRequestBody(equalToJson("""
                        {"transactions":[{"id":"PAY7","amount":"3.00"}]}""")));

        client.refund("ORDTST01OK", null, "idem-2");
        MP.verify(postRequestedFor(urlEqualTo("/v1/orders/ORDTST01OK/refund")).withHeader("X-Idempotency-Key",
                equalTo("idem-2")).withRequestBody(absent()));
        assertThat(client.listRefunds("ORDTST01OK")).extracting(GatewayRefund::id).containsExactly("REF1");
    }

    /**
     * Uma tentativa que deu 422 deixa um registro "failed" na lista; a devolução devolvida é a processada.
     */
    @Test
    @DisplayName("devolução: com um registro 'failed' de tentativa anterior, devolve a processada")
    void refundPicksTheProcessedOne() {
        MP.stubFor(get(urlEqualTo("/v1/orders/ORDTST01R30")).willReturn(json(200, """
                {"id":"ORDTST01R30","total_amount":"10.00","status":"processed",
                 "transactions":{"payments":[{"id":"PAY30","amount":"10.00","status":"processed"}]}}""")));
        MP.stubFor(post(urlEqualTo("/v1/orders/ORDTST01R30/refund")).willReturn(json(201, """
                {"id":"ORDTST01R30","status":"processed","status_detail":"partially_refunded",
                 "transactions":{"refunds":[{"id":"REF-FAIL","amount":"3.00","status":"failed"},
                                            {"id":"REF-OK","amount":"3.00","status":"processed"}]}}""")));

        GatewayRefund refund = client.refund("ORDTST01R30", Money.ofCents(300), "k30");

        assertThat(refund.id()).isEqualTo("REF-OK");
        assertThat(refund.status()).isEqualTo("approved");
    }

    /**
     * Busca por referência, com o período obrigatório.
     */
    @Test
    @DisplayName("busca por external_reference manda o período e lê a lista em 'data'")
    void search() {
        MP.stubFor(get(urlPathEqualTo("/v1/orders")).willReturn(json(200, """
                {"data":[{"id":"ORDTST01S","external_reference":"36","total_amount":"10.00","status":"processed",
                 "status_detail":"accredited","transactions":{"payments":[{"id":"PAY8","amount":"10.00"}]}}]}""")));

        List<GatewayPayment> found = client.search("36");

        assertThat(found).extracting(GatewayPayment::id).containsExactly("ORDTST01S");
        assertThat(found.getFirst().approved()).isTrue();
        MP.verify(getRequestedFor(urlPathEqualTo("/v1/orders")).withQueryParam("external_reference", equalTo("36"))
                .withQueryParam("begin_date", matching("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"))
                .withQueryParam("end_date", matching("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")));
    }

    /**
     * O roteador manda cada id para a API dona dele; as cobranças novas vão para a API escolhida.
     */
    @Test
    @DisplayName("roteador: ORD… vai para a API Orders, id numérico para a API Payments; cobrança nova segue MP_API")
    void routing() {
        MP.stubFor(get(urlEqualTo("/v1/orders/ORDTST01R")).willReturn(json(200, """
                {"id":"ORDTST01R","total_amount":"1.00","status":"processed","status_detail":"accredited"}""")));
        MP.stubFor(get(urlEqualTo("/v1/payments/123")).willReturn(json(200, """
                {"id":123,"status":"approved","transaction_amount":1.0}""")));
        MP.stubFor(post(urlEqualTo("/v1/orders")).willReturn(json(201, """
                {"id":"ORDTST01N","total_amount":"10.00","status":"processed","status_detail":"accredited",
                 "transactions":{"payments":[{"id":"PAY9","amount":"10.00"}]}}""")));

        assertThat(router.getPayment("ORDTST01R").id()).isEqualTo("ORDTST01R");
        assertThat(router.getPayment("123").id()).isEqualTo("123");
        assertThat(router.createCard(card("master", null)).id()).isEqualTo("ORDTST01N");
        MP.verify(getRequestedFor(urlEqualTo("/v1/orders/ORDTST01R")));
        MP.verify(getRequestedFor(urlEqualTo("/v1/payments/123")));
        assertThatThrownBy(() -> new RoutingPaymentGateway(null, null, "outra")).isInstanceOf(
                IllegalStateException.class).hasMessageContaining("MP_API");
    }
}
