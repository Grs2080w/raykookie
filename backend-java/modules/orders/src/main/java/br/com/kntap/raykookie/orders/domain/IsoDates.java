package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.web.NodeInput;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Datas {@code AAAA-MM-DD} que existem no calendário ({@code isoDate} de {@code backend/shared/text.ts}).
 */
public final class IsoDates {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private IsoDates() {
    }

    /**
     * Lê a data.
     *
     * @param value valor do JSON
     * @return a data, ou vazio se não for uma data real
     */
    public static Optional<LocalDate> parse(Object value) {
        String text = NodeInput.text(value);
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
