package br.com.kntap.raykookie.payments;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Aplicação dos testes do módulo: pagamentos (com pedidos, vitrine e clientes) sobre a plataforma de verdade, e as
 * devoluções trocadas por {@link TestDoubles}.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class PaymentsTestApplication {

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
