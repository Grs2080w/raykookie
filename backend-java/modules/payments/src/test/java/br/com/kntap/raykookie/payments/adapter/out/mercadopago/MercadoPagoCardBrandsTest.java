package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.payments.api.CardBrands.CardBrand;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bandeiras aceitas: só os meios de cartão ativos, guardados por um tempo, sem inventar nada quando o Mercado Pago
 * não responde.
 */
class MercadoPagoCardBrandsTest {

    /** Resposta resumida do Mercado Pago (formato real do sandbox). */
    private static final String METHODS = """
            [{"id":"visa","name":"Visa","payment_type_id":"credit_card","status":"active",
              "secure_thumbnail":"https://logos/visa.png"},
             {"id":"visa","name":"Visa Prepaid","payment_type_id":"prepaid_card","status":"active",
              "secure_thumbnail":"https://logos/visa.png"},
             {"id":"debelo","name":"Elo Débito","payment_type_id":"debit_card","status":"active",
              "secure_thumbnail":"https://logos/elo.svg"},
             {"id":"hipercard","name":"Hipercard","payment_type_id":"credit_card","status":"deactive",
              "secure_thumbnail":"https://logos/hiper.png"},
             {"id":"pix","name":"Pix","payment_type_id":"bank_transfer","status":"active",
              "secure_thumbnail":"https://logos/pix.png"}]""";

    /** Mercado Pago falso. */
    private static final WireMockServer MP = new WireMockServer(wireMockConfig().dynamicPort());

    /** Tolerância de relógio do webhook (não usada aqui). */
    private static final Duration SKEW = Duration.ofMinutes(10);

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

    /** Zera os desvios. */
    @BeforeEach
    void reset() {
        MP.resetAll();
    }

    /**
     * Fonte das bandeiras apontando para o servidor falso.
     *
     * @param token {@code MP_ACCESS_TOKEN} ("" = Mercado Pago não configurado)
     * @param ttl   validade da lista
     * @return a fonte
     */
    private static MercadoPagoCardBrands brands(String token, Duration ttl) {
        PaymentSettings settings = new PaymentSettings(token, "s", SKEW, 60, "https://loja.teste", "");
        return new MercadoPagoCardBrands(new MercadoPagoHttp("http://localhost:" + MP.port(), settings), settings, ttl);
    }

    /**
     * Só cartão e só ativo; com o token da loja; a segunda chamada sai do cache.
     */
    @Test
    @DisplayName("lista só meios de cartão ativos (crédito, débito, pré-pago) e guarda a resposta")
    void listsActiveCardMethodsAndCaches() {
        MP.stubFor(get(urlEqualTo("/v1/payment_methods")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(METHODS)));
        MercadoPagoCardBrands source = brands("APP_USR-loja", Duration.ofHours(1));

        List<CardBrand> first = source.list();

        assertThat(first).extracting(CardBrand::id).containsExactly("visa", "visa", "debelo");
        assertThat(first).extracting(CardBrand::type).containsExactly("credit_card", "prepaid_card", "debit_card");
        assertThat(first.getLast()).isEqualTo(new CardBrand("debelo", "Elo Débito", "debit_card",
                "https://logos/elo.svg"));
        assertThat(source.list()).isEqualTo(first);
        MP.verify(1, getRequestedFor(urlEqualTo("/v1/payment_methods")).withHeader("Authorization",
                equalTo("Bearer APP_USR-loja")));
    }

    /**
     * Sem token não chama o Mercado Pago.
     */
    @Test
    @DisplayName("Mercado Pago sem token: lista vazia e nenhuma chamada")
    void notConfigured() {
        assertThat(brands("", Duration.ofHours(1)).list()).isEmpty();
        MP.verify(0, getRequestedFor(urlEqualTo("/v1/payment_methods")));
    }

    /**
     * Falha: mantém a última lista boa e não insiste a cada chamada.
     */
    @Test
    @DisplayName("falha do Mercado Pago: mantém a última lista boa; sem lista boa, vazio (nunca inventa bandeira)")
    void failureKeepsLastGoodList() {
        MP.stubFor(get(urlEqualTo("/v1/payment_methods")).willReturn(aResponse().withStatus(500)));
        MercadoPagoCardBrands cold = brands("APP_USR-loja", Duration.ofHours(1));
        assertThat(cold.list()).isEmpty();
        assertThat(cold.list()).isEmpty();
        MP.verify(1, getRequestedFor(urlEqualTo("/v1/payment_methods")));

        MP.resetAll();
        MP.stubFor(get(urlEqualTo("/v1/payment_methods")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(METHODS)));
        MercadoPagoCardBrands warm = brands("APP_USR-loja", Duration.ZERO);
        assertThat(warm.list()).hasSize(3);
        MP.stubFor(get(urlEqualTo("/v1/payment_methods")).willReturn(aResponse().withStatus(500)));
        assertThat(warm.list()).hasSize(3);
    }
}
