package br.com.kntap.raykookie.audit;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ajudantes para montar as frases e os valores antes/depois do histórico de forma igual em todo caso de uso.
 */
public final class AuditValues {

    /**
     * Classe só de funções estáticas.
     */
    private AuditValues() {
    }

    /**
     * Só os campos que interessam ao histórico.
     *
     * @param row  linha do banco
     * @param keys campos a guardar
     * @return a cópia, ou {@code null} se a linha não existir
     */
    public static Map<String, Object> pick(Map<String, Object> row, List<String> keys) {
        if (row == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        keys.forEach(key -> copy.put(key, row.get(key)));
        return copy;
    }

    /**
     * Número sem zeros à direita, para frases e comparações.
     *
     * @param value número, texto ou {@code null}
     * @return o texto do número ({@code ?} se não houver)
     */
    public static String num(Object value) {
        if (value == null) {
            return "?";
        }
        try {
            return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notANumber) {
            return String.valueOf(value);
        }
    }

    /**
     * Valor em reais para frases ("R$ 12.5").
     *
     * @param value número
     * @return o texto
     */
    public static String brl(Object value) {
        return "R$ " + num(value);
    }

    /**
     * Compara dois números vindos do banco pelo valor (1.50 e 1.5 são iguais).
     *
     * @param a um número
     * @param b outro número
     * @return verdadeiro se iguais
     */
    public static boolean same(Object a, Object b) {
        return Objects.equals(num(a), num(b));
    }

    /**
     * Algum dos campos mudou entre duas linhas?
     *
     * @param before linha de antes (pode ser {@code null})
     * @param after  linha de depois
     * @param keys   campos a comparar
     * @return verdadeiro se algum campo difere
     */
    public static boolean changed(Map<String, Object> before, Map<String, Object> after, List<String> keys) {
        if (before == null || after == null) {
            return true;
        }
        return keys.stream().anyMatch(key -> !same(before.get(key), after.get(key)));
    }
}
