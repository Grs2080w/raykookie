package br.com.kntap.raykookie.insights.domain;

import br.com.kntap.raykookie.kernel.StoreClock;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Meses das metas e do painel: sempre o primeiro dia, no fuso da loja ({@code monthKeyBR}).
 */
public final class Months {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Months() {
    }

    /**
     * Mês atual no fuso da loja (23h30 de 31/10 em São Paulo ainda é outubro, mesmo sendo novembro em UTC).
     *
     * @param clock relógio
     * @return {@code AAAA-MM-01}
     */
    public static LocalDate current(Clock clock) {
        return LocalDate.now(clock.withZone(StoreClock.ZONE)).withDayOfMonth(1);
    }

    /**
     * Mês informado: data real terminada em {@code -01}.
     *
     * @param text texto
     * @return o mês, ou vazio se inválido
     */
    public static Optional<LocalDate> parse(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-01")) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Situação do estoque ({@code stockStatus}).
     *
     * @param stockQty estoque
     * @param minStock mínimo
     * @return {@code zerado}, {@code baixo} ou {@code ok}
     */
    public static String stockStatus(double stockQty, double minStock) {
        if (stockQty <= 0) {
            return "zerado";
        }
        return stockQty <= minStock ? "baixo" : "ok";
    }
}
