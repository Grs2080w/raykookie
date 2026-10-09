package br.com.kntap.raykookie.customers.domain;

import java.util.regex.Pattern;

/**
 * Limpeza de texto digitado pelo cliente, igual à do Node ({@code shared/text.ts} e
 * {@code backend/lib/customer-privacy.ts}).
 */
public final class CustomerText {

    /** Caracteres de controle que o {@code cleanText} do Node remove (um byte nulo derrubava o insert). */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    /** Espaços em sequência (inclui os Unicode, como o {@code \s} do JavaScript). */
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");

    /** E-mail como o {@code isEmail} do Node. */
    private static final Pattern EMAIL = Pattern.compile("(?U)^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    /** Domínio técnico do cliente convidado (link de pagamento): nunca é um e-mail de verdade. */
    private static final Pattern INVALID_DOMAIN = Pattern.compile("(?i)\\.invalid$");

    /**
     * Sem instâncias.
     */
    private CustomerText() {
    }

    /**
     * {@code cleanText}: tira caracteres de controle e espaços das pontas.
     *
     * @param value valor recebido (qualquer tipo)
     * @return o texto limpo ({@code ""} para nulo)
     */
    public static String clean(Object value) {
        return CONTROL.matcher(value == null ? "" : String.valueOf(value)).replaceAll("").strip();
    }

    /**
     * Junta espaços repetidos, tira das pontas e corta no tamanho máximo.
     *
     * @param value valor recebido
     * @param max   tamanho máximo
     * @return o texto
     */
    public static String collapse(Object value, int max) {
        String text = SPACES.matcher(value == null ? "" : String.valueOf(value)).replaceAll(" ").strip();
        return cut(text, max);
    }

    /**
     * Corta no tamanho máximo (em unidades UTF-16, como o {@code slice} do JavaScript).
     *
     * @param text texto
     * @param max  tamanho máximo
     * @return o texto cortado
     */
    public static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Só os dígitos.
     *
     * @param value valor recebido
     * @return os dígitos
     */
    public static String digits(Object value) {
        return (value == null ? "" : String.valueOf(value)).replaceAll("\\D", "");
    }

    /**
     * Diz se o texto é um e-mail aceito.
     *
     * @param email e-mail já limpo
     * @return verdadeiro se tem formato de e-mail, até 120 caracteres, fora do domínio {@code .invalid}
     */
    public static boolean isEmail(String email) {
        return email.length() <= 120 && EMAIL.matcher(email).matches() && !INVALID_DOMAIN.matcher(email).find();
    }

    /**
     * Destino depois do login pelo Google: só caminhos da área do cliente (nunca outro site).
     *
     * @param next destino pedido
     * @return o destino, ou {@code /cliente/pedidos}
     */
    public static String safeNext(Object next) {
        String path = next == null ? "" : String.valueOf(next);
        boolean internal = path.startsWith("/cliente/") && !path.startsWith("//") && !path.contains("\\");
        return internal ? path : "/cliente/pedidos";
    }
}
