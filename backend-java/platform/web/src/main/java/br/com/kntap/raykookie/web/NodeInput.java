package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Leitura dos campos dos formulários do painel com as regras de {@code num} e {@code str} do backend Node
 * ({@code backend/lib/api.ts}): número vindo do JSON vale como está; texto é lido em pt-BR
 * ({@code parseLocaleNumber}); vazio vira o padrão; texto que não é número ou número negativo é recusado.
 *
 * <p>Os números ficam em {@code double} de propósito: são quantidades, tamanhos e porcentagens que o Node
 * grava como chegaram (o Postgres arredonda para as casas da coluna). Dinheiro que entra num cálculo exato
 * passa por {@link Money}.
 */
public final class NodeInput {

    /** Caracteres de controle que o {@code cleanText} do Node remove. */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    /** Ponto seguido de exatamente 3 dígitos, sem vírgula e sem zero à esquerda, é milhar ("1.000" = mil). */
    private static final Pattern THOUSANDS = Pattern.compile("[1-9]\\d{0,2}(\\.\\d{3})+");

    /** Número decimal como o {@code Number()} do JavaScript aceita. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** Inteiro em hexadecimal, octal ou binário ({@code 0x10}, {@code 0o7}, {@code 0b1}). */
    private static final Pattern RADIX = Pattern.compile("0([xXoObB])([0-9a-fA-F]+)");

    /** Tamanho do trecho do valor citado na mensagem de erro. */
    private static final int QUOTE_LENGTH = 20;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private NodeInput() {
    }

    /**
     * {@code num(v, d)} do Node.
     *
     * @param value        valor do JSON (número, texto ou nulo)
     * @param defaultValue valor quando vier vazio
     * @return o número (nunca negativo)
     * @throws InvalidInputException se não for número ou for negativo
     */
    public static double number(Object value, double defaultValue) {

        // ── 1. Vazio (nulo ou só espaços) vira o padrão.
        String text = value == null ? "" : jsString(value).strip();
        if (text.isEmpty()) {
            return defaultValue;
        }

        // ── 2. Número do JSON vale como está; texto é lido em pt-BR.
        double number = value instanceof Number n ? n.doubleValue() : parseLocaleNumber(text);
        if (!Double.isFinite(number)) {
            String quoted = text.length() > QUOTE_LENGTH ? text.substring(0, QUOTE_LENGTH) : text;
            throw new InvalidInputException("“" + quoted + "” não é um número válido.");
        }

        // ── 3. Negativo nunca passa.
        if (number < 0) {
            throw new InvalidInputException("Os valores não podem ser negativos.");
        }
        return number;
    }

    /**
     * {@code num(v)} do Node, com 0 como padrão.
     *
     * @param value valor do JSON
     * @return o número
     */
    public static double number(Object value) {
        return number(value, 0);
    }

    /**
     * {@code str(v)} ({@code cleanText}) do Node: texto sem caracteres de controle e sem espaços nas pontas.
     *
     * @param value valor do JSON (nulo vira vazio)
     * @return o texto
     */
    public static String text(Object value) {
        return value == null ? "" : CONTROL.matcher(jsString(value)).replaceAll("").strip();
    }

    /**
     * Corta o texto como o {@code slice(0, max)} do JavaScript (em unidades UTF-16).
     *
     * @param text texto
     * @param max  tamanho máximo
     * @return o texto cortado
     */
    public static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Valor "verdadeiro" no sentido do JavaScript ({@code if (v)}).
     *
     * @param value valor do JSON
     * @return falso para nulo, {@code false}, 0, NaN e texto vazio
     */
    public static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
            case String s -> !s.isEmpty();
            default -> true;
        };
    }

    /**
     * Id opcional como {@code b.x ? Number(b.x) : null} do Node.
     *
     * @param value valor do JSON
     * @return o id, ou nulo quando ausente
     * @throws InvalidInputException se vier preenchido mas não for um inteiro positivo
     */
    public static Long optionalId(Object value) {
        if (!truthy(value)) {
            return null;
        }
        return positiveId(value).orElseThrow(() -> new InvalidInputException("Algum valor está fora do permitido."));
    }

    /**
     * {@code Number(v)} do JavaScript, aceito só quando dá um inteiro positivo (um id).
     *
     * @param value valor do JSON
     * @return o id, ou vazio quando não for um id
     */
    public static Optional<Long> positiveId(Object value) {
        double number = value instanceof Number n ? n.doubleValue() : jsNumber(value == null ? "" : jsString(value));
        if (number > 0 && number == Math.rint(number) && number <= Integer.MAX_VALUE) {
            return Optional.of((long) number);
        }
        return Optional.empty();
    }

    /**
     * {@code parseLocaleNumber} do Node ({@code backend/shared/text.ts}).
     *
     * @param text texto sem espaços nas pontas
     * @return o número, ou NaN
     */
    static double parseLocaleNumber(String text) {
        if (text.contains(",")) {
            return jsNumber(text.replace(".", "").replaceFirst(",", "."));
        }
        if (THOUSANDS.matcher(text).matches()) {
            return jsNumber(text.replace(".", ""));
        }
        return jsNumber(text);
    }

    /**
     * {@code Number(texto)} do JavaScript.
     *
     * @param raw texto
     * @return o número, ou NaN
     */
    static double jsNumber(String raw) {

        // ── 1. Vazio é zero; "Infinity" é infinito (e o chamador recusa).
        String text = raw.strip();
        if (text.isEmpty()) {
            return 0;
        }
        if (text.matches("[+-]?Infinity")) {
            return text.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }

        // ── 2. Decimal comum, com expoente opcional.
        if (DECIMAL.matcher(text).matches()) {
            return Double.parseDouble(text);
        }

        // ── 3. Inteiro em outra base (sem sinal, como no JavaScript).
        var radix = RADIX.matcher(text);
        if (radix.matches()) {
            int base = switch (Character.toLowerCase(radix.group(1).charAt(0))) {
                case 'x' -> 16;
                case 'o' -> 8;
                default -> 2;
            };
            return parseRadix(radix.group(2), base);
        }
        return Double.NaN;
    }

    /**
     * Inteiro numa base, ou NaN se tiver dígito fora dela.
     *
     * @param digits dígitos
     * @param base   base
     * @return o número
     */
    private static double parseRadix(String digits, int base) {
        try {
            return new BigInteger(digits, base).doubleValue();
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * {@code String(v)} do JavaScript para os tipos que chegam do JSON.
     *
     * @param value valor não nulo
     * @return o texto
     */
    private static String jsString(Object value) {
        return switch (value) {
            case Double d -> jsNumberText(d);
            case Float f -> jsNumberText(f.doubleValue());
            case BigDecimal b -> jsNumberText(b.doubleValue());
            case List<?> list -> String.join(",", list.stream().map(v -> v == null ? "" : jsString(v))
                    .toList());
            case Map<?, ?> map -> "[object Object]";
            default -> String.valueOf(value);
        };
    }

    /**
     * Número como o JavaScript o escreve em texto (inteiro sem ".0").
     *
     * @param number o número
     * @return o texto
     */
    private static String jsNumberText(double number) {
        if (number == Math.rint(number) && Math.abs(number) < 1e21) {
            return BigDecimal.valueOf(number).toBigInteger().toString();
        }
        return Double.toString(number);
    }
}
