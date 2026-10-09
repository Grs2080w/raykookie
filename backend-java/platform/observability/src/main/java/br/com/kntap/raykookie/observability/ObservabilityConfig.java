package br.com.kntap.raykookie.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga os alertas operacionais.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * Webhook de alertas ({@code rayka.alert-webhook-url}, de {@code ALERT_WEBHOOK_URL}).
     *
     * @param url      destino; vazio = sem webhook
     * @param registry métricas
     * @return o cliente
     */
    @Bean
    public AlertWebhookClient alertWebhookClient(@Value("${rayka.alert-webhook-url:}") String url,
            MeterRegistry registry) {
        return new AlertWebhookClient(url, registry);
    }

    /**
     * O alertador.
     *
     * @param registry métricas
     * @param webhook  webhook de alertas
     * @return o alertador
     */
    @Bean
    public OpsAlert opsAlert(MeterRegistry registry, AlertWebhookClient webhook) {
        return new OpsAlert(registry, webhook);
    }
}
