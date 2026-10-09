package br.com.kntap.raykookie.purchasing.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pequenas diferenças entre as expressões regulares e o texto do JavaScript e do Java, para que os leitores de
 * página portados do Node se comportem igual.
 */
public final class Js {

    /** Espaços que o {@code \s} do JavaScript reconhece (o do Java só conhece os ASCII). */
    static final String SPACES = "\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a"
            + "\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

    /** Acentos soltos depois da decomposição NFD. */
    private static final Pattern COMBINING = Pattern.compile("[\\u0300-\\u036f]");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Js() {
    }

    /**
     * Compila uma expressão escrita como no JavaScript: {@code \s} e {@code \S} valem para os espaços Unicode,
     * e com {@link Pattern#CASE_INSENSITIVE} as letras acentuadas também ignoram maiúsculas (como a flag "i").
     *
     * @param regex expressão no estilo JavaScript
     * @param flags flags do {@link Pattern}
     * @return o padrão
     */
    public static Pattern pattern(String regex, int flags) {

        // ── 1. Troca \s e \S, dentro e fora de classes de caracteres.
        StringBuilder out = new StringBuilder();
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char next = regex.charAt(++i);
                out.append(escape(next, inClass));
                continue;
            }
            inClass = c == '[' || (inClass && c != ']');
            out.append(c);
        }

        // ── 2. A flag "i" do JavaScript também vale para letras fora do ASCII.
        int all = (flags & Pattern.CASE_INSENSITIVE) != 0 ? flags | Pattern.UNICODE_CASE : flags;
        return Pattern.compile(out.toString(), all);
    }

    /**
     * Uma sequência de escape traduzida.
     *
     * @param next    o caractere depois da barra
     * @param inClass se está dentro de {@code [...]}
     * @return o trecho em Java
     */
    private static String escape(char next, boolean inClass) {
        if (next == 's') {
            return inClass ? SPACES : "[" + SPACES + "]";
        }
        if (next == 'S' && !inClass) {
            return "[^" + SPACES + "]";
        }
        return "\\" + next;
    }

    /**
     * Compila sem flags.
     *
     * @param regex expressão no estilo JavaScript
     * @return o padrão
     */
    public static Pattern pattern(String regex) {
        return pattern(regex, 0);
    }

    /**
     * {@code s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()}: sem acentos e em minúsculas.
     *
     * @param text texto
     * @return o texto simplificado
     */
    public static String plain(String text) {
        return COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * {@code encodeURIComponent} do JavaScript (espaço vira {@code %20}, não {@code +}).
     *
     * @param text texto
     * @return o texto codificado em UTF-8
     */
    public static String encodeUriComponent(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "-_.!~*'()".indexOf(c) >= 0)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /**
     * {@code Math.round(n * 10 ** d) / 10 ** d} do JavaScript.
     *
     * @param number número
     * @param places casas
     * @return o número arredondado
     */
    public static double round(double number, int places) {
        double scale = Math.pow(10, places);
        return Math.round(number * scale) / scale;
    }
}
