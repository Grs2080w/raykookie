package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova os conversores de {@link Jdbc} contra o Postgres de verdade: {@code NUMERIC(…,2)} vira
 * {@link Money} sem ponto flutuante e as datas chegam no instante e no dia certos.
 */
class JdbcIT extends PostgresTestBase {

    /** Uma linha com valores preenchidos e nulos de cada tipo. */
    private static final String ROW = "SELECT 12.34::numeric(10,2) AS price, NULL::numeric(10,2) AS no_price,"
            + " TIMESTAMPTZ '2026-10-08 12:00:00-03' AS paid_at, NULL::timestamptz AS no_paid_at,"
            + " DATE '2026-10-08' AS day, NULL::date AS no_day";

    /**
     * Dinheiro: valor preenchido vira centavos; nulo é recusado em {@code money} e vira nulo em
     * {@code moneyOrNull}.
     */
    @Test
    @DisplayName("lê NUMERIC(…,2) como Money e trata o nulo")
    void readsMoney() {

        // ── 1. Lê os dois lados do nulo.
        List<Money> read = jdbc().sql(ROW)
                .query((rs, row) -> List.of(Jdbc.money(rs, "price"), Jdbc.moneyOrNull(rs, "price")))
                .single();

        // ── 2. Centavos exatos; nulo obrigatório falha citando a coluna.
        assertThat(read).containsExactly(Money.ofCents(1234), Money.ofCents(1234));
        assertThat(jdbc().sql(ROW).query((rs, row) -> Jdbc.moneyOrNull(rs, "no_price")).optional()).isEmpty();
        assertThatThrownBy(() -> jdbc().sql(ROW).query((rs, row) -> Jdbc.money(rs, "no_price")).single())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no_price");
    }

    /**
     * Datas: {@code TIMESTAMPTZ} vira {@link Instant} e {@code DATE} vira {@link LocalDate}; nulos ficam nulos.
     */
    @Test
    @DisplayName("lê TIMESTAMPTZ como Instant e DATE como LocalDate")
    void readsDates() {

        // ── 1. Lê as colunas preenchidas.
        Instant paidAt = jdbc().sql(ROW).query((rs, row) -> Jdbc.instant(rs, "paid_at")).single();
        LocalDate day = jdbc().sql(ROW).query((rs, row) -> Jdbc.localDate(rs, "day")).single();

        // ── 2. Mesmo instante e mesmo dia; nulos continuam nulos.
        assertThat(paidAt).isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));
        assertThat(day).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(jdbc().sql(ROW).query((rs, row) -> Jdbc.instant(rs, "no_paid_at")).optional()).isEmpty();
        assertThat(jdbc().sql(ROW).query((rs, row) -> Jdbc.localDate(rs, "no_day")).optional()).isEmpty();
    }

    /**
     * Parâmetros: {@link Money} vai como decimal de 2 casas e {@link Instant} como data com fuso; nulos
     * passam como nulos.
     */
    @Test
    @DisplayName("converte Money e Instant em parâmetros que o Postgres grava sem perda")
    void writesParameters() {

        // ── 1. Converte e manda o banco devolver.
        BigDecimal price = Jdbc.decimal(Money.ofCents(1999));
        OffsetDateTime when = Jdbc.timestamp(Instant.parse("2026-10-08T15:00:00Z"));
        String echoed = jdbc().sql("SELECT (:price::numeric(10,2))::text || ' ' || (:when::timestamptz)::text")
                .param("price", price)
                .param("when", when)
                .query(String.class)
                .single();

        // ── 2. O Postgres gravou o mesmo valor e o mesmo instante (no fuso da loja).
        assertThat(echoed).isEqualTo("19.99 2026-10-08 12:00:00-03");
        assertThat(when.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(Jdbc.decimal(null)).isNull();
        assertThat(Jdbc.timestamp(null)).isNull();
    }
}
