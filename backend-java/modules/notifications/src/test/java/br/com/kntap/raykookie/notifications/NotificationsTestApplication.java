package br.com.kntap.raykookie.notifications;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Aplicação dos testes do módulo: notifications (com customers e identity) sobre a plataforma de verdade.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class NotificationsTestApplication {

    /**
     * Relógio real.
     *
     * @return o relógio do sistema
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Métricas em memória.
     *
     * @return o registro
     */
    @Bean
    public SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
