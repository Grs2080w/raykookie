package br.com.kntap.raykookie.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O relógio de 5 minutos respeita o JOBS_ENABLED.
 */
class JobTickerTest {

    /**
     * Desligado, o ciclo não chama o executor (que, aqui, nem existe).
     */
    @Test
    @DisplayName("JOBS_ENABLED=false: o ciclo não roda nada")
    void disabledTickDoesNothing() {
        assertThat(new JobTicker(null, false).tick()).isFalse();
    }
}
