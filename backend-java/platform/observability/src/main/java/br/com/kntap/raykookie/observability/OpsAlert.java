package br.com.kntap.raykookie.observability;

import br.com.kntap.raykookie.web.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Alertas operacionais (G28): o que precisa de olho humano — dinheiro, devolução, tarefa agendada,
 * integração fora. Cada alerta sai como log {@code ERROR} com o marcador {@code ALERT} e os dados em
 * campos, soma em {@code rayka_alerts_total{kind=…}} e, se houver {@code ALERT_WEBHOOK_URL}, vai para o
 * webhook. Nunca lança: alertar não pode piorar o problema.
 */
public class OpsAlert {

    /** Marcador que separa os alertas do resto dos logs de erro. */
    public static final Marker ALERT = MarkerFactory.getMarker("ALERT");

    /** Log dos alertas. */
    private static final Logger LOG = LoggerFactory.getLogger(OpsAlert.class);

    /** Métricas. */
    private final MeterRegistry registry;

    /** Webhook de alertas. */
    private final AlertWebhookClient webhook;

    /**
     * Cria o alertador.
     *
     * @param registry métricas
     * @param webhook  webhook de alertas
     */
    public OpsAlert(MeterRegistry registry, AlertWebhookClient webhook) {
        this.registry = registry;
        this.webhook = webhook;
    }

    /**
     * Problema com um pagamento (ex.: cobrança duplicada, pagamento sem pedido).
     *
     * @param what    o que aconteceu
     * @param context ids e valores envolvidos
     * @return o envio ao webhook (para testes; quem alerta não precisa esperar)
     */
    public CompletableFuture<Boolean> paymentProblem(String what, Map<String, Object> context) {
        return raise(AlertKind.PAYMENT, what, context, null);
    }

    /**
     * Devolução que falhou.
     *
     * @param what    o que aconteceu
     * @param context ids e valores envolvidos
     * @param error   o erro, se houver
     * @return o envio ao webhook
     */
    public CompletableFuture<Boolean> refundFailed(String what, Map<String, Object> context, Throwable error) {
        return raise(AlertKind.REFUND, what, context, error);
    }

    /**
     * Tarefa agendada que falhou.
     *
     * @param job   nome da tarefa
     * @param error o erro
     * @return o envio ao webhook
     */
    public CompletableFuture<Boolean> jobFailed(String job, Throwable error) {
        return raise(AlertKind.JOB, "Tarefa agendada falhou: " + job, Map.of("job", job), error);
    }

    /**
     * Integração externa fora do ar ou mal configurada.
     *
     * @param integration nome da integração (ex.: {@code mercadopago-cartao})
     * @param error       o erro
     * @return o envio ao webhook
     */
    public CompletableFuture<Boolean> integrationDown(String integration, Throwable error) {
        return raise(AlertKind.INTEGRATION, "Integração fora do ar: " + integration,
                Map.of("integration", integration), error);
    }

    /**
     * Registra, conta e envia um alerta.
     *
     * @param kind    tipo
     * @param message mensagem
     * @param context dados
     * @param error   erro, se houver
     * @return o envio ao webhook
     */
    private CompletableFuture<Boolean> raise(AlertKind kind, String message, Map<String, Object> context,
            Throwable error) {

        // ── 1. Junta os dados com o requestId e a causa, sem aceitar mapa nulo.
        Map<String, Object> fields = new LinkedHashMap<>(context == null ? Map.of() : context);
        if (error != null) {
            fields.put("error", error.toString());
        }
        String requestId = RequestIdFilter.current().orElse("-");

        // ── 2. Log ERROR com o marcador ALERT e os dados em campos (logs JSON).
        LOG.atError().addMarker(ALERT)
                .addKeyValue("alertKind", kind.label())
                .addKeyValue("alertContext", fields)
                .setCause(error)
                .log("[ALERTA {}] {} {}", kind.label(), message, fields);

        // ── 3. Conta na métrica por tipo.
        Counter.builder("rayka_alerts").tag("kind", kind.label())
                .description("Alertas operacionais disparados").register(registry).increment();

        // ── 4. Manda ao webhook, se houver.
        return webhook.send(new Alert(kind.label(), message, fields, requestId));
    }
}
