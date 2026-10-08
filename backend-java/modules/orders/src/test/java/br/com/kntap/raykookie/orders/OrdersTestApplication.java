package br.com.kntap.raykookie.orders;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Aplicação dos testes do módulo: pedidos (com vitrine, catálogo e clientes) sobre a plataforma de verdade, e as
 * portas de pagamento e devolução trocadas por {@link FakePorts}.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class OrdersTestApplication {

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
