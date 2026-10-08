package br.com.kntap.raykookie.persistence;

import br.com.kntap.raykookie.kernel.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Conversões entre colunas do Postgres e tipos do domínio, usadas pelos adaptadores de persistência:
 * {@code NUMERIC(…,2)} ↔ {@link Money} (sem ponto flutuante, G12), {@code TIMESTAMPTZ} ↔
 * {@link Instant} e {@code DATE} → {@link LocalDate}.
 */
public final class Jdbc {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Jdbc() {
    }

    /**
     * Lê uma coluna de dinheiro obrigatória.
     *
     * @param rs     linha atual
     * @param column nome da coluna
     * @return o valor em centavos
     * @throws SQLException          se a coluna não puder ser lida
     * @throws IllegalStateException se a coluna for nula
     */
    public static Money money(ResultSet rs, String column) throws SQLException {
        Money value = moneyOrNull(rs, column);
        if (value == null) {
            throw new IllegalStateException("Coluna de dinheiro nula: " + column);
        }
        return value;
    }

    /**
     * Lê uma coluna de dinheiro que pode ser nula.
     *
     * @param rs     linha atual
     * @param column nome da coluna
     * @return o valor em centavos, ou {@code null}
     * @throws SQLException se a coluna não puder ser lida
     */
    public static Money moneyOrNull(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        if (value == null) {
            return null;
        }
        return Money.fromDecimal(value);
    }

    /**
     * Converte dinheiro em parâmetro {@code NUMERIC(…,2)}.
     *
     * @param money valor (pode ser nulo)
     * @return o decimal em reais com 2 casas, ou {@code null}
     */
    public static BigDecimal decimal(Money money) {
        if (money == null) {
            return null;
        }
        return money.toDecimal();
    }

    /**
     * Lê uma coluna {@code TIMESTAMPTZ}.
     *
     * @param rs     linha atual
     * @param column nome da coluna
     * @return o instante, ou {@code null}
     * @throws SQLException se a coluna não puder ser lida
     */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }

    /**
     * Converte um instante em parâmetro {@code TIMESTAMPTZ} (em UTC; o Postgres guarda o instante).
     *
     * @param instant instante (pode ser nulo)
     * @return a data com fuso, ou {@code null}
     */
    public static OffsetDateTime timestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Lê uma coluna {@code DATE}.
     *
     * @param rs     linha atual
     * @param column nome da coluna
     * @return o dia, ou {@code null}
     * @throws SQLException se a coluna não puder ser lida
     */
    public static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
