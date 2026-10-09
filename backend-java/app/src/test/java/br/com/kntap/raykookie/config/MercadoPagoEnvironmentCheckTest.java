package br.com.kntap.raykookie.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.config.RaykookieProperties.Google;
import br.com.kntap.raykookie.config.RaykookieProperties.Mp;
import br.com.kntap.raykookie.observability.AlertWebhookClient;
import br.com.kntap.raykookie.observability.OpsAlert;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Conferência das credenciais do cartão na subida, contra um Mercado Pago falso (G31).
 */
class MercadoPagoEnvironmentCheckTest {

    /** Perfil de /users/me de uma conta real (formato da resposta real, mascarado). */
    private static final String REAL_ACCOUNT = "{\"id\":123,\"nickname\":\"LOJA\",\"tags\":[\"normal\"]}";

    /** Perfil de /users/me de uma conta de teste. */
    private static final String TEST_ACCOUNT = "{\"id\":456,\"nickname\":\"TESTUSER\",\"tags\":[\"normal\",\"test_user\"]}";

    /** O Mercado Pago falso. */
    private WireMockServer mercadoPago;

    /** Métricas (para ver os alertas). */
    private SimpleMeterRegistry registry;

    /** Estado do cartão. */
    private CardPaymentsStatus status;

    /**
     * Sobe o falso respondendo como o real para credenciais boas.
     */
    @BeforeEach
    void start() {
        mercadoPago = new WireMockServer(wireMockConfig().dynamicPort());
        mercadoPago.start();
        mercadoPago.stubFor(get(urlPathEqualTo("/users/me")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(REAL_ACCOUNT)));
        mercadoPago.stubFor(get(urlPathEqualTo("/v1/payment_methods")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        registry = new SimpleMeterRegistry();
        status = new CardPaymentsStatus();
    }

    /**
     * Para o falso.
     */
    @AfterEach
    void stop() {
        mercadoPago.stop();
    }

    /**
     * Configuração com o cartão pedido, apontada para o falso.
     *
     * @param apiBase endereço da API
     * @return a configuração
     */
    private static RaykookieProperties card(String apiBase) {
        Mp mp = new Mp("APP_USR-token", "APP_USR-chave publica", "segredo", apiBase, Duration.ofMinutes(10));
        return new RaykookieProperties("https://raykookie.kntap.com.br", "true", "rayka", "false",
                "0123456789abcdef0123456789abcdef", "downloads", "true", "true", 60, null, null, null, mp,
                new Google(null, null));
    }

    /**
     * A conferência, com ou sem perfil de produção.
     *
     * @param properties configuração
     * @param profiles   perfis ativos
     * @return a conferência
     */
    private MercadoPagoEnvironmentCheck check(RaykookieProperties properties, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        OpsAlert alert = new OpsAlert(registry, new AlertWebhookClient(null, registry));
        return new MercadoPagoEnvironmentCheck(properties, status, alert, environment);
    }

    /**
     * Alertas de integração disparados.
     *
     * @return quantos
     */
    private double integrationAlerts() {
        return registry.find("rayka_alerts").tag("kind", "integration").counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    /**
     * Credenciais aceitas: cartão ligado, sem alerta; o token vai no Bearer e a chave na consulta.
     */
    @Test
    @DisplayName("token e chave pública aceitos: cartão ligado")
    void validCredentials() {

        // ── 1. Confere.
        Optional<String> problem = check(card(mercadoPago.baseUrl()), "prod").verify();

        // ── 2. Ligado, sem alerta, chamadas com as credenciais certas.
        assertThat(problem).isEmpty();
        assertThat(status.enabled()).isTrue();
        assertThat(integrationAlerts()).isZero();
        mercadoPago.verify(getRequestedFor(urlPathEqualTo("/users/me"))
                .withHeader("Authorization", equalTo("Bearer APP_USR-token")));
        mercadoPago.verify(getRequestedFor(urlPathEqualTo("/v1/payment_methods"))
                .withQueryParam("public_key", equalTo("APP_USR-chave publica")));
    }

    /**
     * Token recusado: sobe com o cartão desligado e alerta.
     */
    @Test
    @DisplayName("token recusado: cartão desligado e alerta")
    void rejectedToken() {

        // ── 1. O Mercado Pago recusa o token.
        mercadoPago.stubFor(get(urlPathEqualTo("/users/me")).willReturn(aResponse().withStatus(401)));

        // ── 2. Confere.
        Optional<String> problem = check(card(mercadoPago.baseUrl())).verify();

        // ── 3. Desligado, com o motivo e o alerta.
        assertThat(problem).hasValueSatisfying(reason -> assertThat(reason).contains("MP_ACCESS_TOKEN", "401"));
        assertThat(status.enabled()).isFalse();
        assertThat(integrationAlerts()).isEqualTo(1);
    }

    /**
     * Chave pública recusada (ex.: de outra conta ou apagada): desligado e alerta.
     */
    @Test
    @DisplayName("chave pública recusada: cartão desligado e alerta")
    void rejectedPublicKey() {

        // ── 1. O Mercado Pago recusa a chave.
        mercadoPago.stubFor(get(urlPathEqualTo("/v1/payment_methods"))
                .willReturn(aResponse().withStatus(404)));

        // ── 2. Confere.
        Optional<String> problem = check(card(mercadoPago.baseUrl())).verify();

        // ── 3. Desligado, citando a chave.
        assertThat(problem).hasValueSatisfying(reason -> assertThat(reason).contains("MP_PUBLIC_KEY"));
        assertThat(status.enabled()).isFalse();
        assertThat(integrationAlerts()).isEqualTo(1);
    }

    /**
     * Token de conta de teste em produção: desligado (fora de produção, vale).
     */
    @Test
    @DisplayName("token de conta de teste: desligado em produção, aceito fora dela")
    void testAccountInProduction() {

        // ── 1. O token é de uma conta de teste.
        mercadoPago.stubFor(get(urlPathEqualTo("/users/me")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(TEST_ACCOUNT)));

        // ── 2. Em produção, recusa; fora dela, aceita.
        assertThat(check(card(mercadoPago.baseUrl()), "prod").verify())
                .hasValueSatisfying(reason -> assertThat(reason).contains("conta de teste"));
        assertThat(status.enabled()).isFalse();
        assertThat(check(card(mercadoPago.baseUrl()), "local").verify()).isEmpty();
        assertThat(status.enabled()).isTrue();
    }

    /**
     * Mercado Pago fora do ar na subida: desligado, sem impedir a subida.
     */
    @Test
    @DisplayName("Mercado Pago fora do ar: cartão desligado e alerta, sem exceção")
    void mercadoPagoDown() {

        // ── 1. Ninguém escuta no endereço.
        String base = mercadoPago.baseUrl();
        mercadoPago.stop();

        // ── 2. Confere sem lançar.
        Optional<String> problem = check(card(base)).verify();

        // ── 3. Desligado, com o motivo de rede.
        assertThat(problem).hasValueSatisfying(reason -> assertThat(reason).contains("sem resposta"));
        assertThat(status.enabled()).isFalse();
        assertThat(integrationAlerts()).isEqualTo(1);
    }

    /**
     * Cartão não pedido: a subida não chama o Mercado Pago e o cartão fica desligado.
     */
    @Test
    @DisplayName("PAYMENTS_CARD_ENABLED=false: nenhuma chamada, cartão desligado")
    void cardNotRequested() {

        // ── 1. Configuração sem cartão.
        RaykookieProperties properties = card(mercadoPago.baseUrl());
        RaykookieProperties noCard = new RaykookieProperties(properties.siteUrl(), "true", "rayka", "false",
                properties.downloadSecret(), "downloads", "true", "false", 60, null, null, null, properties.mp(),
                properties.google());

        // ── 2. A subida roda a conferência.
        check(noCard).run(null);

        // ── 3. Nada chamado.
        assertThat(mercadoPago.getAllServeEvents()).isEmpty();
        assertThat(status.enabled()).isFalse();
    }
}
