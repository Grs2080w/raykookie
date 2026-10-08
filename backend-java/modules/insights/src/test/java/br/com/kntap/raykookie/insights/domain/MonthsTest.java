package br.com.kntap.raykookie.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mês no fuso da loja, mês informado e situação do estoque.
 */
class MonthsTest {

    /**
     * Virada do mês.
     */
    @Test
    @DisplayName("23h30 de 31/10 em São Paulo ainda é outubro, mesmo sendo novembro em UTC")
    void currentMonthInStoreZone() {
        Clock lateOctober = Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"), ZoneOffset.UTC);
        assertThat(Months.current(lateOctober)).isEqualTo(LocalDate.parse("2026-10-01"));
        Clock november = Clock.fixed(Instant.parse("2026-11-01T03:30:00Z"), ZoneOffset.UTC);
        assertThat(Months.current(november)).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    /**
     * Mês informado e estoque.
     */
    @Test
    @DisplayName("mês: só AAAA-MM-01 real; estoque zerado, baixo ou ok")
    void parseAndStock() {
        assertThat(Months.parse("2026-10-01")).contains(LocalDate.parse("2026-10-01"));
        assertThat(Months.parse("2026-10-02")).isEmpty();
        assertThat(Months.parse("2026-13-01")).isEmpty();
        assertThat(Months.parse(null)).isEmpty();
        assertThat(Months.stockStatus(0, 5)).isEqualTo("zerado");
        assertThat(Months.stockStatus(3, 5)).isEqualTo("baixo");
        assertThat(Months.stockStatus(6, 5)).isEqualTo("ok");
    }
}
