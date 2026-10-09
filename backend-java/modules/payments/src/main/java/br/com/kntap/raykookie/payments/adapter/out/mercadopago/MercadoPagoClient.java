package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.payments.domain.MpDates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Adaptador do Mercado Pago ({@code backend/lib/mercadopago.ts}): {@code RestClient} com tempo limite de conexão de
 * 3 s e de leitura de 10 s; toda falha vira {@link GatewayException}.
 */
@Component
public class MercadoPagoClient implements PaymentGateway {

    /** Tempo limite de conexão. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** Tempo limite de leitura. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Cliente HTTP. */
    private final RestClient http;

    /** Configuração. */
    private final PaymentSettings settings;

    /**
     * Cria o adaptador.
     *
     * @param apiBase  {@code MP_API_BASE}
     * @param settings configuração
     */
    public MercadoPagoClient(@Value("${rayka.mp.api-base:https://api.mercadopago.com}") String apiBase,
            PaymentSettings settings) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().baseUrl(apiBase).requestFactory(factory).build();
        this.settings = settings;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createPix(PixCharge charge) {

        // ── 1. Corpo da cobrança Pix.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", reais(charge.amount()));
        body.put("description", charge.description());
        body.put("payment_method_id", "pix");
        body.put("payer", Map.of("email", charge.email(), "first_name", charge.firstName()));
        body.put("external_reference", charge.reference());
        body.put("date_of_expiration", MpDates.format(charge.expiresAt()));
        body.put("notification_url", settings.siteUrl("/api/webhooks/mercadopago"));

        // ── 2. Sem copia e cola não há Pix.
        GatewayPayment payment = payment(call(HttpMethod.POST, "/v1/payments", body, charge.idempotencyKey()));
        if (payment.qrCode() == null || payment.qrCode().isEmpty()) {
            throw new GatewayException(200, "Mercado Pago não devolveu o Pix copia e cola", null);
        }
        return payment;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createCard(CardCharge charge) {

        // ── 1. Cartão: só o token, nunca o número.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", reais(charge.amount()));
        body.put("token", charge.token());
        body.put("description", charge.description());
        body.put("installments", charge.installments());
        body.put("payment_method_id", charge.methodId());
        if (charge.issuerId() != null) {
            body.put("issuer_id", charge.issuerId());
        }

        // ── 2. Pagador, referência e aviso.
        body.put("payer", Map.of("email", charge.email(), "first_name", charge.firstName(), "identification",
                Map.of("type", "CPF", "number", charge.cpf())));
        body.put("external_reference", charge.reference());
        body.put("notification_url", settings.siteUrl("/api/webhooks/mercadopago"));
        return payment(call(HttpMethod.POST, "/v1/payments", body, charge.idempotencyKey()));
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment getPayment(String id) {
        return payment(call(HttpMethod.GET, "/v1/payments/{id}", null, null, id));
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(String id) {
        call(HttpMethod.PUT, "/v1/payments/{id}", Map.of("status", "cancelled"), null, id);
    }

    /** {@inheritDoc} */
    @Override
    public GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey) {
        Map<String, Object> body = amountOrNull == null ? Map.of() : Map.of("amount", reais(amountOrNull));
        return refund(call(HttpMethod.POST, "/v1/payments/{id}/refunds", body, idempotencyKey, paymentId));
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayRefund> listRefunds(String paymentId) {
        List<GatewayRefund> refunds = new ArrayList<>();
        call(HttpMethod.GET, "/v1/payments/{id}/refunds", null, null, paymentId)
                .forEach(node -> refunds.add(refund(node)));
        return refunds;
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayPayment> search(String externalReference) {
        List<GatewayPayment> payments = new ArrayList<>();
        call(HttpMethod.GET, "/v1/payments/search?sort=date_created&criteria=desc&external_reference={ref}", null,
                null, externalReference).path("results").forEach(node -> payments.add(payment(node)));
        return payments;
    }

    /**
     * Faz a chamada.
     *
     * @param method         método
     * @param path           caminho (modelo de URI; as variáveis são codificadas)
     * @param body           corpo (nulo = nenhum)
     * @param idempotencyKey chave (nula = nenhuma)
     * @param variables      variáveis do caminho
     * @return a resposta
     */
    private JsonNode call(HttpMethod method, String path, Object body, String idempotencyKey, Object... variables) {

        // ── 1. Requisição com o token e a chave de idempotência.
        RestClient.RequestBodySpec request = http.method(method).uri(path, variables)
                .header("Authorization", "Bearer " + settings.accessToken()).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("X-Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.body(JSON.writeValueAsString(body));
        }

        // ── 2. Resposta: erro HTTP e falha de rede viram GatewayException.
        try {
            return request.exchange((req, res) -> {
                String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new GatewayException(res.getStatusCode().value(), text, null);
                }
                return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            });
        } catch (ResourceAccessException e) {
            throw new GatewayException(0, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Pagamento da resposta.
     *
     * @param node JSON
     * @return o pagamento
     */
    static GatewayPayment payment(JsonNode node) {
        JsonNode td = node.path("point_of_interaction").path("transaction_data");
        return new GatewayPayment(node.path("id").asString(""), node.path("status").asString(""),
                node.path("status_detail").asString(""), text(node.path("external_reference")),
                money(node.path("transaction_amount")), money(node.path("transaction_amount_refunded")),
                text(td.path("qr_code")), text(td.path("qr_code_base64")));
    }

    /**
     * Devolução da resposta.
     *
     * @param node JSON
     * @return a devolução
     */
    static GatewayRefund refund(JsonNode node) {
        return new GatewayRefund(node.path("id").asString(""), money(node.path("amount")),
                node.path("status").asString(""));
    }

    /**
     * Texto ou nulo.
     *
     * @param node campo
     * @return o texto
     */
    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    /**
     * Valor em reais (número) como dinheiro; ausente é zero.
     *
     * @param node campo
     * @return o valor
     */
    private static Money money(JsonNode node) {
        if (!node.isNumber()) {
            return Money.ZERO;
        }
        return Money.fromDecimal(node.decimalValue().setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Reais com 2 casas, como número no JSON.
     *
     * @param amount valor
     * @return o valor em reais
     */
    private static BigDecimal reais(Money amount) {
        return amount.toDecimal().setScale(2, RoundingMode.UNNECESSARY);
    }
}
