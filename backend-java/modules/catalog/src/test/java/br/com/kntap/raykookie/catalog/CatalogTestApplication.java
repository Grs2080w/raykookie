package br.com.kntap.raykookie.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Aplicação dos testes do módulo: catalog (com identity e notifications) sobre a plataforma de verdade.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class CatalogTestApplication {

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
