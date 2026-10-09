package br.com.kntap.raykookie.catalog.api;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tamanho da embalagem lido do nome de um produto ("Chocolate 1,01kg", "Granulado 400 g", "Leite 1L",
 * "Cx 12 un"), sempre na unidade-base do estoque: porte exato de {@code backend/shared/pack.ts}.
 */
public final class Packs {

    /** Espaços do {@code \s} do JavaScript. */
    private static final Pattern JS_SPACES = Pattern.compile(
            "[\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+");

    /** "2x500g": o tamanho de cada um e quantos. */
    private static final Pattern MULTI =
            Pattern.compile("(\\d+)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|g|gr|ml|l|lt)\\b");

    /** Peso ou volume ("1,5 L"). */
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(kg|g|gr|ml|l|lt)\\b");

    /** Unidades com palavra antes ("cx c/ 12 unidades"). */
    private static final Pattern UNITS = Pattern.compile(
            "(?:c/|cx\\.?|caixa|pct\\.?|pacote|com|\\b)\\s*(\\d+)\\s*(?:un\\b|uns\\b|unid\\w*|pcs|pç\\w*|peças)");

    /** Unidades sem palavra antes ("50 un"). */
    private static final Pattern UNITS_PLAIN = Pattern.compile("\\b(\\d+)\\s*(?:un|uns|unid\\w*|pcs)\\b");

    /** Ponto de milhar ("1.000.000"). */
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");

    /** Começo numérico que o {@code parseFloat} aceita. */
    private static final Pattern FLOAT_PREFIX = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Packs() {
    }

    /**
     * Lê o tamanho da embalagem no nome ({@code parsePack}).
     *
     * @param name nome do produto
     * @return o tamanho na unidade-base, se o nome trouxer um
     */
    public static Optional<Pack> parse(String name) {

        // ── 1. Minúsculas e espaços simples.
        String text = JS_SPACES.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ");

        // ── 2. "2x500g" vira 1000 g.
        Matcher multi = MULTI.matcher(text);
        if (multi.find()) {
            Optional<Pack> each = convert(number(multi.group(2)), multi.group(3));
            if (each.isPresent()) {
                double size = Math.round(each.get().size() * Double.parseDouble(multi.group(1)) * 1000) / 1000.0;
                return Optional.of(new Pack(size, each.get().unit()));
            }
        }

        // ── 3. O último tamanho citado costuma ser o da embalagem.
        Matcher size = SIZE.matcher(text);
        Optional<Pack> last = Optional.empty();
        boolean found = false;
        while (size.find()) {
            found = true;
            last = convert(number(size.group(1)), size.group(2));
        }
        if (found && last.isPresent()) {
            return Optional.of(new Pack(Math.round(last.get().size() * 1000) / 1000.0, last.get().unit()));
        }

        // ── 4. Sem peso nem volume: procura unidades.
        return units(text);
    }

    /**
     * Unidades no nome ("50 un", "cx c/ 12 unidades").
     *
     * @param text nome normalizado
     * @return o pacote em unidades, se houver
     */
    private static Optional<Pack> units(String text) {
        Matcher units = UNITS.matcher(text);
        if (!units.find()) {
            units = UNITS_PLAIN.matcher(text);
            if (!units.find()) {
                return Optional.empty();
            }
        }
        double count = Double.parseDouble(units.group(1));
        return count > 0 ? Optional.of(new Pack(count, "un")) : Optional.empty();
    }

    /**
     * Converte para a unidade-base.
     *
     * @param value número lido
     * @param unit  unidade do nome
     * @return o tamanho na unidade-base, ou vazio se o número não for positivo
     */
    private static Optional<Pack> convert(double value, String unit) {
        if (!(value > 0)) {
            return Optional.empty();
        }
        return Optional.of(switch (unit) {
            case "kg" -> new Pack(value * 1000, "g");
            case "g", "gr" -> new Pack(value, "g");
            case "l", "lt" -> new Pack(value * 1000, "ml");
            default -> new Pack(value, "ml");
        });
    }

    /**
     * O {@code num} de {@code pack.ts}: tira os pontos de milhar e lê com {@code parseFloat}.
     *
     * @param raw número do nome ("1,01", "1.000")
     * @return o número, ou NaN
     */
    static double number(String raw) {

        // ── 1. Cada ponto sai se houver vírgula ou se ele for de milhar até o fim do texto.
        StringBuilder out = new StringBuilder();
        boolean hasComma = raw.indexOf(',') >= 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean thousands = c == '.' && (hasComma || THOUSANDS.matcher(raw.substring(Math.max(0, i - 3)))
                    .matches());
            if (!thousands) {
                out.append(c);
            }
        }

        // ── 2. A primeira vírgula vira ponto e o parseFloat lê o começo numérico.
        Matcher prefix = FLOAT_PREFIX.matcher(out.toString().replaceFirst(",", "."));
        return prefix.lookingAt() ? Double.parseDouble(prefix.group()) : Double.NaN;
    }

    /**
     * Custo por unidade-base.
     *
     * @param price preço
     * @param size  tamanho
     * @return o custo por g, ml ou un
     */
    public static double pricePerBase(double price, double size) {
        return size > 0 ? price / size : 0;
    }

    /**
     * Preço por kg, L ou unidade, para comparar fornecedores ({@code pricePerLabel}).
     *
     * @param price preço
     * @param size  tamanho
     * @param unit  unidade-base
     * @return por exemplo {@code R$ 44,99/kg}
     */
    public static String pricePerLabel(double price, double size, String unit) {
        double perBase = pricePerBase(price, size);
        if ("un".equals(unit)) {
            return "R$ " + PricingEngine.fixed2(perBase) + "/un";
        }
        return "R$ " + PricingEngine.fixed2(perBase * 1000) + "/" + ("g".equals(unit) ? "kg" : "L");
    }

    /**
     * Tamanho da embalagem.
     *
     * @param size tamanho na unidade-base
     * @param unit {@code g}, {@code ml} ou {@code un}
     */
    public record Pack(double size, String unit) {
    }
}
