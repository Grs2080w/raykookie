package br.com.kntap.raykookie.ratelimit;

import br.com.kntap.raykookie.persistence.PersistenceConfig;
import br.com.kntap.raykookie.testing.ManualClock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Aplicação mínima dos testes deste módulo: persistência (pool e Flyway, que cria {@code rate_limits})
 * e o limitador, com um relógio que o teste avança à mão.
 */
@SpringBootApplication
@Import(PersistenceConfig.class)
public class RateLimitTestApplication {

    /**
     * Relógio controlado pelo teste.
     *
     * @return o relógio
     */
    @Bean
    public ManualClock clock() {
        return new ManualClock(Instant.parse("2026-10-08T12:00:00Z"));
    }
}
