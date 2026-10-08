package br.com.kntap.raykookie.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manda alertas para um webhook (ex.: um canal de chat), se {@code ALERT_WEBHOOK_URL} existir. O envio
 * é assíncrono, com tempo limite de 3 s, e nunca lança: alerta que não chega vira um aviso no log e a
 * métrica {@code rayka_alert_webhook_failures_total}, sem atrapalhar quem alertou.
 */
public class AlertWebhookClient {

    /** Tempo limite de conexão e de resposta. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** Log das falhas de envio. */
    private static final Logger LOG = LoggerFactory.getLogger(AlertWebhookClient.class);

    /** Destino; nulo = sem webhook. */
    private final URI url;

    /** Cliente HTTP. */
    private final HttpClient http;

    /** Serializador do corpo. */
    private final JsonMapper json;

    /** Envios que falharam. */
    private final Counter failures;

    /**
     * Cria o cliente.
     *
     * @param url      destino ({@code ALERT_WEBHOOK_URL}); vazio ou nulo = não envia
     * @param registry métricas
     */
    public AlertWebhookClient(String url, MeterRegistry registry) {
        this.url = url == null || url.isBlank() ? null : URI.create(url.strip());
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.json = JsonMapper.builder().build();
        this.failures = Counter.builder("rayka_alert_webhook_failures")
                .description("Alertas que não chegaram ao webhook")
                .register(registry);
    }

    /**
     * Diz se há webhook configurado.
     *
     * @return verdadeiro se os alertas são enviados
     */
    public boolean enabled() {
        return url != null;
    }

    /**
     * Envia o alerta em segundo plano.
     *
     * @param alert o alerta
     * @return o envio em andamento (verdadeiro se o webhook respondeu 2xx); já concluído se não há webhook
     */
    public CompletableFuture<Boolean> send(Alert alert) {

        // ── 1. Sem webhook, nada a fazer.
        if (url == null) {
            return CompletableFuture.completedFuture(false);
        }

        // ── 2. Monta o POST JSON com tempo limite (um contexto que não vira JSON não derruba quem alertou).
        String body;
        try {
            body = json.writeValueAsString(alert);
        } catch (JacksonException unserializable) {
            return CompletableFuture.completedFuture(delivered(null, unserializable));
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // ── 3. Envia sem bloquear; qualquer falha só conta e avisa.
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, error) -> delivered(response, error));
    }

    /**
     * Registra o resultado do envio.
     *
     * @param response resposta, se houve
     * @param error    erro de rede, se houve
     * @return verdadeiro se o webhook respondeu 2xx
     */
    private boolean delivered(HttpResponse<Void> response, Throwable error) {
        boolean ok = error == null && response.statusCode() / 100 == 2;
        if (!ok) {
            failures.increment();
            String reason = error == null ? "status " + response.statusCode() : error.toString();
            LOG.warn("Alerta não chegou ao webhook: {}", reason);
        }
        return ok;
    }
}
