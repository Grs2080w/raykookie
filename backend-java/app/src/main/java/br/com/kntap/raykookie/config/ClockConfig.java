package br.com.kntap.raykookie.config;

import br.com.kntap.raykookie.kernel.StoreClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O relógio do sistema, num lugar só: todo componente que precisa de "agora" recebe este {@link Clock}
 * (e os testes trocam por um relógio controlado).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Relógio real, em UTC (o fuso da loja é aplicado pelo {@link StoreClock}).
     *
     * @return o relógio do sistema
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Relógio da loja ({@code America/Sao_Paulo}) sobre o relógio do sistema.
     *
     * @param clock relógio do sistema
     * @return o relógio da loja
     */
    @Bean
    public StoreClock storeClock(Clock clock) {
        return new StoreClock(clock);
    }
}
