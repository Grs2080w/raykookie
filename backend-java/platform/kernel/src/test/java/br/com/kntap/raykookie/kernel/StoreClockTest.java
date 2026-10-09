package br.com.kntap.raykookie.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O dia e o mês da loja seguem o fuso de São Paulo, não o do servidor.
 */
class StoreClockTest {

    /**
     * Cria um relógio da loja parado em um instante UTC.
     *
     * @param instant instante em ISO-8601 UTC
     * @return relógio da loja
     */
    private static StoreClock at(String instant) {
        return new StoreClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    /**
     * Duas e meia da manhã em UTC ainda é a noite do dia anterior em São Paulo.
     */
    @Test
    @DisplayName("hoje respeita o fuso America/Sao_Paulo")
    void todayUsesStoreZone() {

        // ── 1. 02:30Z de 8/out são 23:30 de 7/out em São Paulo.
        assertThat(at("2026-10-08T02:30:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 7));

        // ── 2. 03:00Z de 8/out já são 00:00 de 8/out.
        assertThat(at("2026-10-08T03:00:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(StoreClock.ZONE).isEqualTo(ZoneId.of("America/Sao_Paulo"));
    }

    /**
     * A chave do mês é sempre o dia 1, inclusive na virada de mês pelo fuso.
     */
    @Test
    @DisplayName("monthKey devolve o dia 1 do mês da loja")
    void monthKeyIsFirstDay() {

        // ── 1. Meio do mês.
        assertThat(at("2026-10-15T15:00:00Z").monthKey()).isEqualTo(LocalDate.of(2026, 10, 1));

        // ── 2. Virada: 02:00Z de 1/out ainda é 30/set em São Paulo.
        assertThat(at("2026-10-01T02:00:00Z").monthKey()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    /**
     * O relógio do sistema existe e devolve uma data plausível.
     */
    @Test
    @DisplayName("o relógio do sistema devolve a data de hoje")
    void systemClockWorks() {

        // ── 1. Compara com o relógio do próprio JDK no mesmo fuso.
        LocalDate now = LocalDate.now(StoreClock.ZONE);
        assertThat(StoreClock.system().today()).isBetween(now.minusDays(1), now.plusDays(1));
    }
}
