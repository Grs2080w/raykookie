package br.com.kntap.raykookie.payments.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Datas no formato que o Mercado Pago aceita ({@code mpDate}): ISO 8601 sempre em horário de Brasília
 * ({@code -03:00}), com milissegundos.
 */
public final class MpDates {

    /** Formato com o fuso fixo. */
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
            .withZone(ZoneOffset.ofHours(-3));

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private MpDates() {
    }

    /**
     * Formata o instante.
     *
     * @param instant instante
     * @return {@code AAAA-MM-DDTHH:MM:SS.mmm-03:00}
     */
    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }
}
