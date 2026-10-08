package br.com.kntap.raykookie.scheduling;

import br.com.kntap.raykookie.observability.ObservabilityConfig;
import br.com.kntap.raykookie.persistence.PersistenceConfig;
import br.com.kntap.raykookie.testing.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Aplicação dos testes deste módulo: persistência (Flyway cria {@code job_runs}), alertas e as tarefas,
 * com relógio controlado e métricas em memória.
 */
@SpringBootApplication
@Import({PersistenceConfig.class, ObservabilityConfig.class})
public class SchedulingTestApplication {

    /**
     * Relógio controlado pelo teste.
     *
     * @return o relógio
     */
    @Bean
    public ManualClock clock() {
        return new ManualClock(Instant.parse("2026-10-08T12:00:00Z"));
    }

    /**
     * Métricas em memória (para contar os alertas).
     *
     * @return o registro
     */
    @Bean
    public SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
