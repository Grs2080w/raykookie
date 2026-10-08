package br.com.kntap.raykookie.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.web.RequestIdFilter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Alertas operacionais: log com marcador, métrica e webhook que nunca derruba quem alertou (G28).
 */
class OpsAlertTest {

    /**
     * Valor que o Jackson não consegue escrever: o getter lança.
     */
    public static final class Exploding {

        /**
         * Lança sempre.
         *
         * @return nunca devolve
         */
        public String getValue() {
            throw new IllegalStateException("não serializa");
        }
    }

    /** Destino do webhook nos testes. */
    private WireMockServer webhook;

    /** Métricas em memória. */
    private SimpleMeterRegistry registry;

    /** Captura dos logs do alertador. */
    private ListAppender<ILoggingEvent> logs;

    /**
     * Sobe o webhook falso e começa a capturar os logs.
     */
    @BeforeEach
    void start() {
        webhook = new WireMockServer(wireMockConfig().dynamicPort());
        webhook.start();
        webhook.stubFor(post(urlEqualTo("/alertas")).willReturn(aResponse().withStatus(204)));
        registry = new SimpleMeterRegistry();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(OpsAlert.class)).addAppender(logs);
    }

    /**
     * Para o webhook, a captura e limpa o MDC.
     */
    @AfterEach
    void stop() {
        webhook.stop();
        ((Logger) LoggerFactory.getLogger(OpsAlert.class)).detachAppender(logs);
        MDC.clear();
    }

    /**
     * O alerta chega ao webhook com o requestId da requisição, é registrado e contado.
     *
     * @throws Exception se o envio não terminar
     */
    @Test
    @DisplayName("alerta chega ao webhook com o requestId, sai no log com ALERT e soma na métrica")
    void alertReachesWebhook() throws Exception {

        // ── 1. Um alerta de pagamento dentro de uma requisição.
        OpsAlert alert = new OpsAlert(registry, new AlertWebhookClient(webhook.baseUrl() + "/alertas", registry));
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        boolean delivered = alert.paymentProblem("Cobrança duplicada", Map.of("pedido", 17))
                .get(5, TimeUnit.SECONDS);

        // ── 2. O webhook recebeu o JSON completo.
        assertThat(delivered).isTrue();
        webhook.verify(postRequestedFor(urlEqualTo("/alertas"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"kind":"payment","message":"Cobrança duplicada","context":{"pedido":17},"requestId":"req-42"}
                        """)));

        // ── 3. Log ERROR com o marcador e a métrica por tipo.
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("ERROR");
            assertThat(event.getMarkerList()).contains(OpsAlert.ALERT);
            assertThat(event.getFormattedMessage()).contains("Cobrança duplicada", "pedido=17");
        });
        assertThat(registry.get("rayka_alerts").tag("kind", "payment").counter().count()).isEqualTo(1);
    }

    /**
     * Webhook fora do ar: o alerta não lança e a falha é contada.
     *
     * @throws Exception se o envio não terminar
     */
    @Test
    @DisplayName("webhook fora do ar: não lança e conta rayka_alert_webhook_failures")
    void webhookDownNeverThrows() throws Exception {

        // ── 1. Uma porta onde ninguém escuta.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        OpsAlert alert = new OpsAlert(registry,
                new AlertWebhookClient("http://127.0.0.1:" + closedPort + "/alertas", registry));

        // ── 2. Alerta de tarefa agendada, fora de requisição.
        boolean delivered = alert.jobFailed("refunds.retry-due", new IOException("gateway fora"))
                .get(10, TimeUnit.SECONDS);

        // ── 3. Não chegou, mas ninguém viu exceção; a falha e o alerta foram contados.
        assertThat(delivered).isFalse();
        assertThat(registry.get("rayka_alert_webhook_failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("rayka_alerts").tag("kind", "job").counter().count()).isEqualTo(1);
    }

    /**
     * Webhook que responde erro também conta como falha.
     *
     * @throws Exception se o envio não terminar
     */
    @Test
    @DisplayName("webhook que responde 500: falha contada")
    void webhookErrorStatusCounts() throws Exception {

        // ── 1. Webhook que recusa.
        webhook.stubFor(post(urlEqualTo("/recusa")).willReturn(aResponse().withStatus(500)));
        OpsAlert alert = new OpsAlert(registry, new AlertWebhookClient(webhook.baseUrl() + "/recusa", registry));

        // ── 2. Alerta de integração e de devolução.
        boolean integration = alert.integrationDown("mercadopago-cartao", new IllegalStateException("token"))
                .get(5, TimeUnit.SECONDS);
        boolean refund = alert.refundFailed("Devolução falhou", Map.of("devolucao", 3), null)
                .get(5, TimeUnit.SECONDS);

        // ── 3. As duas contadas como falha de envio.
        assertThat(integration).isFalse();
        assertThat(refund).isFalse();
        assertThat(registry.get("rayka_alert_webhook_failures").counter().count()).isEqualTo(2);
    }

    /**
     * Sem webhook configurado: só log e métrica.
     *
     * @throws Exception se o envio não terminar
     */
    @Test
    @DisplayName("sem ALERT_WEBHOOK_URL: só log e métrica")
    void noWebhook() throws Exception {

        // ── 1. Cliente sem destino.
        AlertWebhookClient none = new AlertWebhookClient(" ", registry);
        OpsAlert alert = new OpsAlert(registry, none);

        // ── 2. Nada enviado, nada contado como falha.
        assertThat(none.enabled()).isFalse();
        assertThat(alert.paymentProblem("sem webhook", null).get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(registry.get("rayka_alert_webhook_failures").counter().count()).isZero();
        assertThat(registry.get("rayka_alerts").tag("kind", "payment").counter().count()).isEqualTo(1);
    }

    /**
     * Contexto que não vira JSON não derruba quem alertou.
     *
     * @throws Exception se o envio não terminar
     */
    @Test
    @DisplayName("contexto que não vira JSON: falha contada, sem exceção")
    void unserializableContext() throws Exception {

        // ── 1. Um valor que o Jackson não sabe escrever.
        OpsAlert alert = new OpsAlert(registry, new AlertWebhookClient(webhook.baseUrl() + "/alertas", registry));
        Map<String, Object> context = Map.of("coisa", new Exploding());

        // ── 2. Não lança; conta a falha.
        assertThat(alert.paymentProblem("contexto estranho", context).get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(registry.get("rayka_alert_webhook_failures").counter().count()).isEqualTo(1);
    }
}
