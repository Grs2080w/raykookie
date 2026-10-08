package br.com.kntap.raykookie.identity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Aplicação dos testes do módulo: o identity com a plataforma de verdade (banco, borda web, segurança,
 * limites, tarefas), sem o app.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class IdentityTestApplication {

    /** Propriedades comuns: a dona é "dona" e as contas de teste vêm de db/testdata. */
    public static final String[] PROPERTIES = {
        "rayka.shop-owner=dona",
        "rayka.jobs-enabled=false",
        "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                + "classpath:db/testdata",
    };

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
