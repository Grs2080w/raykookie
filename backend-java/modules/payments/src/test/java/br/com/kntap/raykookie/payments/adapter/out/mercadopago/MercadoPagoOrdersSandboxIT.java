package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.CardBrands.CardBrand;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * O adaptador da API Orders contra o <b>sandbox real</b> do Mercado Pago: crédito, pré-pago, débito, Pix, 3DS,
 * recusa, cancelamento, devolução e busca. Só roda com {@code RUN_MP_SANDBOX=1} (nenhuma execução normal do
 * {@code verify} toca na rede), com o par de teste do vendedor em {@code MP_SANDBOX_ACCESS_TOKEN} e
 * {@code MP_SANDBOX_PUBLIC_KEY}. Os ids dos pedidos criados vão para {@code target/mp-sandbox-orders.txt}.
 *
 * <pre>
 * RUN_MP_SANDBOX=1 MP_SANDBOX_ACCESS_TOKEN=… MP_SANDBOX_PUBLIC_KEY=… \
 *   ./mvnw -pl modules/payments -am verify -Dit.test=MercadoPagoOrdersSandboxIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_MP_SANDBOX", matches = "1")
class MercadoPagoOrdersSandboxIT {

    /** API do Mercado Pago. */
    private static final String API = "https://api.mercadopago.com";

    /** Valor das compras de teste. */
    private static final Money TEN = Money.ofCents(1000);

    /** E-mail de comprador aceito no sandbox (precisa terminar em {@code @testuser.com}). */
    private static final String BUYER = "ana@testuser.com";

    /** CPF de teste do Mercado Pago. */
    private static final String CPF = "12345678909";

    /** Relatório com os ids gerados. */
    private static final Path REPORT = Path.of("target", "mp-sandbox-orders.txt");

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Chave pública de teste. */
    private static String publicKey;

    /** Adaptador sem 3DS (os aprovados saem direto). */
    private static MercadoPagoOrdersClient plain;

    /** Adaptador com 3DS ({@code on_fraud_risk}). */
    private static MercadoPagoOrdersClient withThreeDs;

    /** Lê as credenciais de teste e monta os adaptadores. */
    @BeforeAll
    static void setUp() throws IOException {
        String token = System.getenv("MP_SANDBOX_ACCESS_TOKEN");
        publicKey = System.getenv("MP_SANDBOX_PUBLIC_KEY");
        assumeTrue(token != null && !token.isBlank() && publicKey != null && !publicKey.isBlank(),
                "defina MP_SANDBOX_ACCESS_TOKEN e MP_SANDBOX_PUBLIC_KEY");
        PaymentSettings settings = new PaymentSettings(token, "s", Duration.ofMinutes(10), 60, "https://loja.teste", "");
        MercadoPagoHttp http = new MercadoPagoHttp(API, settings);
        plain = new MercadoPagoOrdersClient(http, "never");
        withThreeDs = new MercadoPagoOrdersClient(http, "on_fraud_risk");
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, "Pedidos criados no sandbox em " + Instant.now() + "\n", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Anota no relatório e na saída.
     *
     * @param what  o que foi testado
     * @param order o pedido criado
     */
    private static void note(String what, GatewayPayment order) throws IOException {
        String line = what + ": " + order.id() + " (" + order.status() + "/" + order.statusDetail() + ")";
        System.out.println("[sandbox] " + line);
        Files.writeString(REPORT, line + "\n", StandardOpenOption.APPEND);
    }

    /**
     * Gera um token de cartão pela API pública (como o site e o app fazem; o número nunca passa pelo backend).
     *
     * @param number número do cartão de teste
     * @param name   titular (o nome decide o resultado no sandbox)
     * @return o token
     */
    private static String token(String number, String name) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(java.util.Map.of("card_number", number, "expiration_month", "11",
                "expiration_year", "2030", "security_code", number.startsWith("3753") ? "1234" : "123", "cardholder",
                java.util.Map.of("name", name, "identification", java.util.Map.of("type", "CPF", "number", CPF))));
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(API + "/v1/card_tokens?public_key=" + publicKey)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("tokenizar o cartão de teste: " + res.body()).isEqualTo(201);
        return JSON.readTree(res.body()).path("id").asString("");
    }

    /**
     * Cobrança no cartão como o sistema monta.
     *
     * @param token    token do cartão
     * @param methodId id do meio, como o BIN devolve
     * @param typeId   tipo do cartão
     * @return a cobrança
     */
    private static PaymentGateway.CardCharge charge(String token, String methodId, String typeId) {
        return new PaymentGateway.CardCharge("sandbox-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString(), TEN, "Pedido de teste - Raykookie", token, 1, methodId, null, BUYER,
                "Ana", CPF, new PaymentGateway.Buyer("Souza", "(11) 91234-5678", "device-sandbox", "203.0.113.9"),
                typeId);
    }

    /**
     * Devolução com nova tentativa: o Mercado Pago termina de processar o pedido de forma assíncrona, e uma devolução
     * logo depois da compra pode voltar {@code 422 Post processing rejected the operation}. Na vida real a devolução
     * vem dias depois (e o módulo de devoluções já tenta de novo); no teste, esperamos o pedido assentar.
     *
     * @param id     pedido
     * @param amount valor (nulo = devolução total)
     * @return a devolução
     */
    private static GatewayRefund refundWhenSettled(String id, Money amount) throws InterruptedException {
        GatewayException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                return plain.refund(id, amount, "sandbox-refund-" + id + "-" + (amount == null ? "all" : amount.cents()));
            } catch (GatewayException e) {
                if (e.status() != 422) {
                    throw e;
                }
                last = e;
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    /**
     * Crédito aprovado, consulta e devolução parcial.
     */
    @Test
    @DisplayName("crédito Mastercard aprovado; consulta; devolução parcial de R$ 3,00")
    void creditApprovedAndPartialRefund() throws Exception {
        GatewayPayment pay = plain.createCard(charge(token("5480832801033311", "APRO"), "master", "credit_card"));
        note("crédito (Mastercard)", pay);
        assertThat(pay.id()).startsWith("ORD");
        assertThat(pay.approved()).isTrue();
        assertThat(pay.amount()).isEqualTo(TEN);
        assertThat(pay.externalReference()).startsWith("sandbox-");

        GatewayPayment fetched = plain.getPayment(pay.id());
        assertThat(fetched.approved()).isTrue();
        assertThat(fetched.refundedAmount()).isEqualTo(Money.ZERO);

        GatewayRefund refund = refundWhenSettled(pay.id(), Money.ofCents(300));
        assertThat(refund.status()).isEqualTo("approved");
        assertThat(refund.amount()).isEqualTo(Money.ofCents(300));
        GatewayPayment after = plain.getPayment(pay.id());
        assertThat(after.refundedAmount()).isEqualTo(Money.ofCents(300));
        assertThat(after.refundedOrChargedBack()).isTrue();
        assertThat(plain.listRefunds(pay.id())).hasSize(1);
    }

    /**
     * Crédito Visa aprovado e devolução total.
     */
    @Test
    @DisplayName("crédito Visa aprovado; devolução total (sem corpo) deixa o pedido 'refunded'")
    void creditVisaAndFullRefund() throws Exception {
        GatewayPayment pay = plain.createCard(charge(token("4235647728025682", "APRO"), "visa", "credit_card"));
        note("crédito (Visa)", pay);
        assertThat(pay.approved()).isTrue();

        GatewayRefund refund = refundWhenSettled(pay.id(), null);
        assertThat(refund.status()).isEqualTo("approved");
        assertThat(refund.amount()).isEqualTo(TEN);
        GatewayPayment after = plain.getPayment(pay.id());
        assertThat(after.status()).isEqualTo("refunded");
        assertThat(after.refundedOrChargedBack()).isTrue();
    }

    /**
     * Recusa por saldo: vem em HTTP 402 e o adaptador devolve recusa com o código que o sistema explica.
     */
    @Test
    @DisplayName("crédito recusado por saldo (FUND): recusa com cc_rejected_insufficient_amount, sem exceção")
    void creditRefused() throws Exception {
        GatewayPayment pay = plain.createCard(charge(token("5480832801033311", "FUND"), "master", "credit_card"));
        note("crédito recusado (FUND)", pay);
        assertThat(pay.status()).isEqualTo("rejected");
        assertThat(pay.approved()).isFalse();
        assertThat(pay.open()).isFalse();
        assertThat(pay.statusDetail()).isEqualTo("cc_rejected_insufficient_amount");
    }

    /**
     * Pré-pago: o cartão Elo de teste é pré-pago pelo BIN; não leva parcelas.
     */
    @Test
    @DisplayName("pré-pago (Elo de teste, prepaid_card): aprovado, sem parcelas")
    void prepaid() throws Exception {
        GatewayPayment pay = plain.createCard(charge(token("5067766783888311", "APRO"), "elo", "prepaid_card"));
        note("pré-pago (Elo)", pay);
        assertThat(pay.id()).startsWith("ORD");
        assertThat(pay.approved()).isTrue();
        assertThat(plain.getPayment(pay.id()).approved()).isTrue();
    }

    /**
     * Débito: a API exige o id do débito ({@code debelo}). A conta de teste pode não ter o débito habilitado; aí o
     * teste é pulado (e o motivo aparece), em vez de passar em falso.
     */
    @Test
    @DisplayName("débito (id debelo, debit_card): aprovado, ou pulado se a conta de teste não tem débito habilitado")
    void debit() throws Exception {
        try {
            GatewayPayment pay = plain.createCard(charge(token("5067766783888311", "APRO"), "debelo", "debit_card"));
            note("débito (Elo Débito)", pay);
            assertThat(pay.approved()).isTrue();
        } catch (GatewayException e) {
            // o pedido chegou à API com o id e o tipo certos; só falta a conta de teste aceitar débito
            assumeTrue(!String.valueOf(e.body()).contains("payment_method_not_allowed_for_collector"),
                    "o vendedor de teste não tem o débito habilitado (payment_method_not_allowed_for_collector)");
            throw e;
        }
    }

    /**
     * 3DS: com o titular de teste que exige desafio, o pedido fica pendente com o endereço do desafio.
     */
    @Test
    @DisplayName("3DS (titular APRO-CHOK): pedido pendente com o endereço do desafio")
    void threeDsChallenge() throws Exception {
        GatewayPayment pay = withThreeDs.createCard(charge(token("4235647728025682", "APRO-CHOK"), "visa",
                "credit_card"));
        note("3DS (desafio)", pay);
        assertThat(pay.status()).isEqualTo("pending");
        assertThat(pay.open()).isTrue();
        assertThat(pay.challengePending()).isTrue();
        assertThat(pay.challengeUrl()).startsWith("https://www.mercadopago.com.br/auth/");
        assertThat(withThreeDs.getPayment(pay.id()).challengePending()).isTrue();
    }

    /**
     * Pix: QR, consulta, busca por referência e cancelamento.
     */
    @Test
    @DisplayName("Pix: nasce pendente com o copia e cola; a busca por referência acha; cancelar fecha")
    void pix() throws Exception {
        String reference = "sandbox-pix-" + UUID.randomUUID().toString().substring(0, 8);
        GatewayPayment pix = plain.createPix(new PaymentGateway.PixCharge(reference, UUID.randomUUID().toString(),
                Money.ofCents(640), "Pedido de teste Pix - Raykookie", BUYER, "Ana", Instant.now().plus(
                        Duration.ofHours(1))));
        note("Pix", pix);
        assertThat(pix.id()).startsWith("ORD");
        assertThat(pix.status()).isEqualTo("pending");
        assertThat(pix.qrCode()).isNotBlank();
        assertThat(pix.qrBase64()).isNotBlank();
        assertThat(pix.amount()).isEqualTo(Money.ofCents(640));

        List<GatewayPayment> found = List.of();
        for (int i = 0; i < 8 && found.isEmpty(); i++) {
            found = plain.search(reference);
            if (found.isEmpty()) {
                Thread.sleep(1500);
            }
        }
        assertThat(found).extracting(GatewayPayment::id).contains(pix.id());

        plain.cancel(pix.id());
        GatewayPayment cancelled = plain.getPayment(pix.id());
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.open()).isFalse();
    }

    /**
     * Bandeiras da conta de teste.
     */
    @Test
    @DisplayName("bandeiras: a conta de teste lista crédito e pré-pago ativos")
    void brands() {
        PaymentSettings settings = new PaymentSettings(System.getenv("MP_SANDBOX_ACCESS_TOKEN"), "s",
                Duration.ofMinutes(10), 60, "https://loja.teste", "");
        List<CardBrand> list = new MercadoPagoCardBrands(new MercadoPagoHttp(API, settings), settings,
                Duration.ofHours(1)).list();
        assertThat(list).isNotEmpty();
        assertThat(list).extracting(CardBrand::type).contains("credit_card", "prepaid_card");
        assertThat(list).allSatisfy(b -> assertThat(b.logo()).startsWith("https://"));
    }
}
