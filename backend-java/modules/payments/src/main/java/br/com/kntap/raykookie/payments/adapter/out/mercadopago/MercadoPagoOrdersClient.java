package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import com.mercadopago.client.common.IdentificationRequest;
import com.mercadopago.client.common.PhoneRequest;
import com.mercadopago.client.order.OrderConfigRequest;
import com.mercadopago.client.order.OrderCreateRequest;
import com.mercadopago.client.order.OrderItemRequest;
import com.mercadopago.client.order.OrderOnlineConfig;
import com.mercadopago.client.order.OrderPayerRequest;
import com.mercadopago.client.order.OrderPaymentMethodRequest;
import com.mercadopago.client.order.OrderPaymentRequest;
import com.mercadopago.client.order.OrderRefundPaymentRequest;
import com.mercadopago.client.order.OrderRefundRequest;
import com.mercadopago.client.order.OrderTransactionRequest;
import com.mercadopago.client.order.OrderTransactionSecurity;
import com.mercadopago.net.MPSearchRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Adaptador da API Orders do Mercado Pago ({@code /v1/orders}). O "id do pagamento" que o resto do sistema guarda é
 * o <b>id do pedido</b> ({@code ORD…}, em teste {@code ORDTST…}): é com ele que se consulta, cancela e devolve. Os
 * status do Mercado Pago são traduzidos para os de sempre ({@code approved}, {@code pending}, {@code rejected}…) e os
 * motivos de recusa para os códigos {@code cc_rejected_*} que já têm texto para o cliente.
 */
@Component
public class MercadoPagoOrdersClient implements PaymentGateway {

    /** Prefixo do id de pedido desta API (em teste, {@code ORDTST}). */
    public static final String ID_PREFIX = "ORD";

    /** Menor validade aceita para o Pix. */
    private static final Duration MIN_PIX_EXPIRATION = Duration.ofMinutes(30);

    /** Janela de busca de pedidos por referência (o Mercado Pago exige o período). */
    private static final Duration SEARCH_WINDOW = Duration.ofDays(7);

    /** Formato de data da busca. */
    private static final DateTimeFormatter SEARCH_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** Valores aceitos em {@code MP_3DS}. */
    private static final List<String> THREE_DS_MODES = List.of("never", "on_fraud_risk");

    /** Motivos de recusa da API Orders → código {@code cc_rejected_*} que o sistema já sabe explicar. */
    private static final Map<String, String> REJECTIONS = Map.ofEntries(
            Map.entry("insufficient_amount", "cc_rejected_insufficient_amount"),
            Map.entry("card_insufficient_amount", "cc_rejected_insufficient_amount"),
            Map.entry("high_risk", "cc_rejected_high_risk"),
            Map.entry("required_call_for_authorize", "cc_rejected_call_for_authorize"),
            Map.entry("card_disabled", "cc_rejected_card_disabled"),
            Map.entry("max_attempts_exceeded", "cc_rejected_max_attempts"),
            Map.entry("bad_filled_card_data", "cc_rejected_bad_filled_other"),
            Map.entry("invalid_installments", "cc_rejected_invalid_installments"),
            Map.entry("rejected_by_issuer", "cc_rejected_other_reason"),
            Map.entry("amount_limit_exceeded", "cc_rejected_other_reason"),
            Map.entry("3ds_challenge_expired", "cc_rejected_3ds_challenge"));

    /** Cliente HTTP. */
    private final MercadoPagoHttp http;

    /** Quando pedir o desafio 3DS ({@code never} ou {@code on_fraud_risk}). */
    private final String threeDs;

    /**
     * Cria o adaptador.
     *
     * @param http    chamadas HTTP ao Mercado Pago
     * @param threeDs {@code MP_3DS}: {@code on_fraud_risk} (padrão) ou {@code never}
     */
    public MercadoPagoOrdersClient(MercadoPagoHttp http, @Value("${rayka.mp.three-ds:on_fraud_risk}") String threeDs) {
        if (!THREE_DS_MODES.contains(threeDs)) {
            throw new IllegalStateException("MP_3DS precisa ser never ou on_fraud_risk (veio '" + threeDs + "')");
        }
        this.http = http;
        this.threeDs = threeDs;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createPix(PixCharge charge) {

        // ── 1. Pedido com um pagamento Pix; a validade vai em minutos inteiros (mínimo de 30).
        Duration expiration = Duration.ofMinutes(Math.max(MIN_PIX_EXPIRATION.toMinutes(),
                Duration.between(Instant.now(), charge.expiresAt()).toMinutes()));
        OrderPaymentRequest payment = OrderPaymentRequest.builder().amount(reais(charge.amount()).toPlainString())
                .expirationTime(expiration.toString())
                .paymentMethod(OrderPaymentMethodRequest.builder().id("pix").type("bank_transfer").build()).build();
        OrderCreateRequest request = base(charge.reference(), charge.description(), charge.amount(), payment)
                .payer(OrderPayerRequest.builder().email(charge.email()).firstName(charge.firstName()).build())
                .build();

        // ── 2. Sem copia e cola não há Pix.
        GatewayPayment created = payment(http.call((sdk, options) -> sdk.orders().create(request, options),
                charge.idempotencyKey(), Map.of()));
        if (created.qrCode() == null || created.qrCode().isEmpty()) {
            throw new GatewayException(200, "Mercado Pago não devolveu o Pix copia e cola", null);
        }
        return created;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createCard(CardCharge charge) {

        // ── 1. Cartão: só o token. Débito e pré-pago não têm parcelas. O id é o do BIN, sem mexer: no débito a API
        //       exige o id do débito ("debelo"), e não a bandeira.
        String type = cardType(charge);
        OrderPaymentMethodRequest.OrderPaymentMethodRequestBuilder method = OrderPaymentMethodRequest.builder()
                .id(charge.methodId()).type(type).token(charge.token())
                .statementDescriptor(MercadoPagoClient.STATEMENT_DESCRIPTOR);
        if ("credit_card".equals(type)) {
            method.installments(charge.installments());
        }
        OrderPaymentRequest payment = OrderPaymentRequest.builder().amount(reais(charge.amount()).toPlainString())
                .paymentMethod(method.build()).build();

        // ── 2. Pagador, comprador e desafio 3DS quando o Mercado Pago julgar necessário.
        OrderCreateRequest.OrderCreateRequestBuilder builder = base(charge.reference(), charge.description(),
                charge.amount(), payment).payer(payer(charge));
        if (!"never".equals(threeDs)) {
            builder.config(OrderConfigRequest.builder().online(OrderOnlineConfig.builder().transactionSecurity(
                    OrderTransactionSecurity.builder().validation(threeDs).liabilityShift("required").build())
                    .build()).build());
        }
        OrderCreateRequest request = builder.build();

        // ── 3. Antifraude (device ID) e cobrança; a recusa vem como HTTP 402 com o pedido em "data".
        PaymentGateway.Buyer buyer = charge.buyer();
        Map<String, String> headers = buyer != null && present(buyer.deviceId())
                ? Map.of("X-meli-session-id", buyer.deviceId())
                : Map.of();
        try {
            return payment(http.call((sdk, options) -> sdk.orders().create(request, options),
                    charge.idempotencyKey(), headers));
        } catch (GatewayException e) {
            JsonNode data = MercadoPagoHttp.parse(e.body()).path("data");
            if (e.status() == 402 && data.isObject()) {
                return payment(data);
            }
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment getPayment(String id) {
        return payment(getJson(id));
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(String id) {
        // a API Orders exige a chave de idempotência aqui também; uma por pedido, para repetir ser seguro
        http.call((sdk, options) -> sdk.orders().cancel(id, options), "rayka-cancel-" + id, Map.of());
    }

    /** {@inheritDoc} */
    @Override
    public GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey) {

        // ── 1. Total: sem corpo. Parcial: o corpo diz de qual pagamento do pedido e quanto.
        JsonNode response;
        if (amountOrNull == null) {
            response = http.call((sdk, options) -> sdk.orders().refund(paymentId, options), idempotencyKey,
                    Map.of());
        } else {
            String transaction = getJson(paymentId).path("transactions").path("payments").path(0).path("id")
                    .asString("");
            OrderRefundRequest request = OrderRefundRequest.builder().transactions(List.of(
                    OrderRefundPaymentRequest.builder().id(transaction).amount(reais(amountOrNull).toPlainString())
                            .build())).build();
            response = http.call((sdk, options) -> sdk.orders().refund(paymentId, request, options),
                    idempotencyKey, Map.of());
        }

        // ── 2. A devolução nova é a última processada da lista (uma tentativa que deu 422 deixa um registro
        //       "failed" para trás, sem devolver dinheiro; a lista pode ter os dois).
        JsonNode refunds = response.path("transactions").path("refunds");
        if (!refunds.isArray() || refunds.isEmpty()) {
            throw new GatewayException(200, "Mercado Pago não devolveu a devolução", null);
        }
        JsonNode created = refunds.get(refunds.size() - 1);
        for (JsonNode candidate : refunds) {
            if ("processed".equals(candidate.path("status").asString(""))) {
                created = candidate;
            }
        }
        return refund(created);
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayRefund> listRefunds(String paymentId) {
        List<GatewayRefund> refunds = new ArrayList<>();
        getJson(paymentId).path("transactions").path("refunds").forEach(node -> refunds.add(refund(node)));
        return refunds;
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayPayment> search(String externalReference) {
        Instant now = Instant.now();
        MPSearchRequest request = MPSearchRequest.builder().limit(50).offset(0).filters(Map.of("external_reference",
                externalReference, "begin_date", SEARCH_DATE.format(now.minus(SEARCH_WINDOW)), "end_date",
                SEARCH_DATE.format(now.plusSeconds(3600)))).build();
        List<GatewayPayment> found = new ArrayList<>();
        http.call((sdk, options) -> sdk.orders().search(request, options), null, Map.of()).path("data")
                .forEach(node -> found.add(payment(node)));
        return found;
    }

    /**
     * Consulta o pedido e devolve o JSON dele.
     *
     * @param id id do pedido
     * @return o pedido
     */
    private JsonNode getJson(String id) {
        return http.call((sdk, options) -> sdk.orders().get(id, options), null, Map.of());
    }

    /**
     * Corpo comum a Pix e cartão.
     *
     * @param reference   {@code external_reference}
     * @param description descrição do pedido
     * @param amount      valor
     * @param payment     o pagamento
     * @return o pedido, ainda sem pagador
     */
    private static OrderCreateRequest.OrderCreateRequestBuilder base(String reference, String description,
            Money amount, OrderPaymentRequest payment) {

        // ── 1. O pedido inteiro como um item.
        OrderItemRequest item = OrderItemRequest.builder().title(description)
                .unitPrice(reais(amount).toPlainString()).quantity(1).categoryId("food").build();

        // ── 2. Pedido online processado na hora, com a referência e o pagamento.
        return OrderCreateRequest.builder().type("online").processingMode("automatic")
                .totalAmount(reais(amount).toPlainString()).externalReference(reference).description(description)
                .items(List.of(item)).transactions(OrderTransactionRequest.builder().payments(List.of(payment))
                        .build());
    }

    /**
     * Pagador do cartão: nome, CPF e telefone (separado em DDD e número).
     *
     * @param charge cobrança
     * @return o pagador
     */
    private static OrderPayerRequest payer(CardCharge charge) {

        // ── 1. E-mail, nome e CPF.
        PaymentGateway.Buyer buyer = charge.buyer();
        OrderPayerRequest.OrderPayerRequestBuilder payer = OrderPayerRequest.builder().email(charge.email())
                .firstName(charge.firstName()).identification(IdentificationRequest.builder().type("CPF")
                        .number(charge.cpf()).build());
        if (buyer != null && present(buyer.lastName())) {
            payer.lastName(buyer.lastName());
        }

        // ── 2. Telefone separado em DDD e número.
        String phone = buyer == null || buyer.phone() == null ? "" : buyer.phone().replaceAll("\\D", "");
        if (phone.length() >= 10) {
            payer.phone(PhoneRequest.builder().areaCode(phone.substring(0, 2)).number(phone.substring(2)).build());
        }
        return payer.build();
    }

    /**
     * Tipo do cartão: o que o cliente informou, ou débito se a bandeira vem com o prefixo {@code deb}.
     *
     * @param charge cobrança
     * @return {@code credit_card}, {@code debit_card} ou {@code prepaid_card}
     */
    static String cardType(CardCharge charge) {
        if (present(charge.typeId())) {
            return charge.typeId();
        }
        return charge.methodId().startsWith("deb") ? "debit_card" : "credit_card";
    }

    /**
     * Traduz um pedido da API Orders para o pagamento que o resto do sistema entende.
     *
     * @param node pedido
     * @return o pagamento (o id é o do pedido)
     */
    static GatewayPayment payment(JsonNode node) {

        // ── 1. O primeiro (e único) pagamento do pedido.
        JsonNode pay = node.path("transactions").path("payments").path(0);
        JsonNode method = pay.path("payment_method");
        String status = status(node.path("status").asString(""));

        // ── 2. Motivo, desafio 3DS e valor.
        String detail = pay.path("status_detail").asString(node.path("status_detail").asString(""));
        String challenge = "pending_challenge".equals(detail) ? text(method.path("transaction_security").path("url"))
                : null;
        Money amount = money(pay.path("amount").isMissingNode() ? node.path("total_amount") : pay.path("amount"));
        return new GatewayPayment(node.path("id").asString(""), status, "rejected".equals(status) ? reason(detail)
                : detail, text(node.path("external_reference")), amount, refunded(node),
                text(method.path("qr_code")), text(method.path("qr_code_base64")), challenge);
    }

    /**
     * Status do pedido → status que o sistema usa.
     *
     * @param orderStatus status da API Orders
     * @return {@code approved}, {@code pending}, {@code in_process}, {@code rejected}, {@code cancelled}…
     */
    static String status(String orderStatus) {
        return switch (orderStatus) {
            case "processed" -> "approved";
            case "refunded" -> "refunded";
            case "charged_back" -> "charged_back";
            case "action_required" -> "pending";
            case "failed" -> "rejected";
            case "canceled", "expired" -> "cancelled";
            default -> "in_process";
        };
    }

    /**
     * Motivo de recusa → código {@code cc_rejected_*}; o que não tem tradução segue como veio.
     *
     * @param detail motivo da API Orders
     * @return o código
     */
    static String reason(String detail) {
        return REJECTIONS.getOrDefault(detail, detail);
    }

    /**
     * Soma das devoluções já feitas no pedido.
     *
     * @param node pedido
     * @return o valor devolvido
     */
    private static Money refunded(JsonNode node) {
        long cents = 0;
        for (JsonNode refund : node.path("transactions").path("refunds")) {
            if ("processed".equals(refund.path("status").asString("processed"))) {
                cents += money(refund.path("amount")).cents();
            }
        }
        return Money.ofCents(cents);
    }

    /**
     * Devolução da API Orders.
     *
     * @param node devolução
     * @return a devolução ({@code processed} vira {@code approved})
     */
    static GatewayRefund refund(JsonNode node) {
        String status = node.path("status").asString("");
        return new GatewayRefund(node.path("id").asString(""), money(node.path("amount")),
                "processed".equals(status) ? "approved" : status);
    }

    /**
     * Texto de um campo, ou nulo se não existir.
     *
     * @param node campo
     * @return o texto
     */
    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    /**
     * Confere se há texto.
     *
     * @param text texto
     * @return {@code true} se não é nulo nem em branco
     */
    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * Valor em reais vindo como texto ({@code "10.00"}) ou número.
     *
     * @param node valor
     * @return o dinheiro (zero se não houver)
     */
    private static Money money(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.asString("").isBlank()) {
            return Money.ZERO;
        }
        return Money.fromDecimal(new BigDecimal(node.asString("0")).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Valor em reais com duas casas.
     *
     * @param amount dinheiro
     * @return o valor
     */
    private static BigDecimal reais(Money amount) {
        return amount.toDecimal().setScale(2, RoundingMode.UNNECESSARY);
    }
}
