package br.com.kntap.raykookie.purchasing.domain;

import br.com.kntap.raykookie.purchasing.domain.NfceParser.NfceItem;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Casamento dos itens da nota com o estoque e cálculo do que entra: porte exato da segunda metade de
 * {@code backend/lib/nfce.ts}.
 */
public final class NfceMatching {

    /** Palavras que não ajudam a reconhecer um produto. */
    private static final Set<String> STOP = Set.of("de", "da", "do", "das", "dos", "e", "com", "sem", "em", "para",
            "tipo", "tp", "un", "und", "unid", "cx", "pct", "pc", "pac", "sc", "kg", "g", "gr", "ml", "l", "lt",
                    "litro",
            "lata", "pote", "sache", "sch");

    /** Abreviações das notas. */
    private static final Map<String, String> ABBR = Map.ofEntries(Map.entry("cond", "condensado"),
            Map.entry("choc", "chocolate"), Map.entry("mant", "manteiga"), Map.entry("marg", "margarina"),
            Map.entry("ac", "acucar"), Map.entry("refin", "refinado"), Map.entry("cr", "creme"),
            Map.entry("leit", "leite"), Map.entry("farin", "farinha"), Map.entry("trig", "trigo"),
            Map.entry("integr", "integral"), Map.entry("desn", "desnatado"), Map.entry("semi", "semi"),
            Map.entry("mor", "morango"), Map.entry("bau", "baunilha"), Map.entry("ess", "essencia"),
            Map.entry("fermen", "fermento"), Map.entry("gran", "granulado"), Map.entry("confeit", "confeiteiro"),
            Map.entry("am", "amendoa"));

    /** Medida solta ("395g", "1kg", "12"). */
    private static final Pattern MEASURE = Pattern.compile("\\d+(g|gr|kg|ml|l|lt|un|und)?");

    /** Tamanho escrito no nome. */
    private static final Pattern SIZE = Js.pattern("(?<![a-z\\d])(\\d+(?:[.,]\\d+)?)\\s*(kg|g|gr|ml|l|lt)(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private NfceMatching() {
    }

    /**
     * Palavras do nome: sem acento, sem medidas e sem palavras vazias, com as abreviações expandidas.
     *
     * @param name nome
     * @return as palavras
     */
    public static List<String> nameTokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String token : Js.plain(name).replaceAll("(\\d+)[.,](\\d+)", "$1$2").split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !MEASURE.matcher(token).matches() && !STOP.contains(token)) {
                tokens.add(ABBR.getOrDefault(token, token));
            }
        }
        return tokens;
    }

    /**
     * Duas palavras iguais, ou uma começo da outra (com 4 letras ou mais).
     *
     * @param a uma palavra
     * @param b outra
     * @return verdadeiro se casam
     */
    private static boolean same(String a, String b) {
        return a.equals(b) || (Math.min(a.length(), b.length()) >= 4 && (a.startsWith(b) || b.startsWith(a)));
    }

    /**
     * Acha o ingrediente que corresponde ao item ({@code matchIngredient}). {@code exact} = todas as palavras do
     * ingrediente aparecem no item (com 2+ palavras); senão é sugestão, que o app pergunta antes de somar.
     *
     * @param itemName nome do item na nota
     * @param list     ingredientes da dona
     * @return o casamento, se houver
     */
    public static Optional<Match> matchIngredient(String itemName, List<Ingredient> list) {

        // ── 1. O ingrediente com mais palavras em comum (proporção do ingrediente pesa mais).
        List<String> item = nameTokens(itemName);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        Ingredient best = null;
        int bestHit = 0;
        int bestTotal = 0;
        double bestScore = 0;
        for (Ingredient ingredient : list) {
            List<String> words = nameTokens(ingredient.name());
            long hit = words.stream().filter(word -> item.stream().anyMatch(token -> same(token, word))).count();
            double score = (double) hit / words.size() * 0.75 + (double) hit / item.size() * 0.25 + hit * 0.01;
            if (!words.isEmpty() && hit > 0 && (best == null || score > bestScore)) {
                best = ingredient;
                bestHit = (int) hit;
                bestTotal = words.size();
                bestScore = score;
            }
        }

        // ── 2. Tudo casado = soma direto (com cuidado nos nomes de uma palavra); mais da metade = sugestão.
        if (best == null) {
            return Optional.empty();
        }
        boolean full = bestHit == bestTotal;
        boolean exact = full && (bestTotal >= 2 || item.size() == 1);
        return full || (double) bestHit / bestTotal > 0.5
                ? Optional.of(new Match(best.id(), best.name(), exact))
                : Optional.empty();
    }

    /**
     * Tamanho escrito no nome ("395G", "1,5 KG", "200ML", "1L").
     *
     * @param name nome
     * @return unidade ({@code g} ou {@code ml}) e tamanho
     */
    public static Optional<Size> sizeInName(String name) {
        Matcher match = SIZE.matcher(Js.plain(name));
        if (!match.find()) {
            return Optional.empty();
        }
        double value = NfceParser.brNumber(match.group(1));
        if (!Double.isFinite(value) || value <= 0) {
            return Optional.empty();
        }
        return Optional.of(switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "kg" -> new Size("g", value * 1000);
            case "l", "lt" -> new Size("ml", value * 1000);
            case "ml" -> new Size("ml", value);
            default -> new Size("g", value);
        });
    }

    /**
     * Quanto entra no estoque (em embalagens) e o preço da embalagem ({@code planAdd}).
     *
     * @param item       item da nota
     * @param ingredient ingrediente do estoque
     * @return o plano
     */
    public static AddPlan planAdd(NfceItem item, Ingredient ingredient) {

        // ── 1. Vendido a granel (kg/L) e o estoque em g/ml: converte para embalagens do cadastro.
        double size = ingredient.packageSize() > 0 ? ingredient.packageSize() : 1;
        Size bulk = bulk(item.unit());
        if (bulk != null && bulk.unit().equals(ingredient.unit())) {
            double amount = item.qty() * bulk.size();
            return new AddPlan(Js.round(amount / size, 3), Js.round(item.unitPrice() / bulk.size() * size, 2),
                    text(Js.round(amount, 3)) + " " + ingredient.unit() + " ≈ " + text(Js.round(amount / size, 3))
                            + " embalagem(ns)");
        }

        // ── 2. Vendido por embalagem com o tamanho no nome, diferente do cadastro: ajusta.
        Optional<Size> named = sizeInName(item.name());
        if (named.isPresent() && named.get().unit().equals(ingredient.unit())
                && Math.abs(named.get().size() - size) > 0.001) {
            double packs = item.qty() * named.get().size() / size;
            return new AddPlan(Js.round(packs, 3), Js.round(item.unitPrice() / named.get().size() * size, 2),
                    text(item.qty()) + " × " + text(named.get().size()) + " " + ingredient.unit() + " = "
                            + text(Js.round(packs, 3)) + " embalagem(ns) de " + text(size) + " " + ingredient.unit());
        }

        // ── 3. Mesma embalagem do cadastro.
        return new AddPlan(Js.round(item.qty(), 3), Js.round(item.unitPrice(), 2),
                text(Js.round(item.qty(), 3)) + " embalagem(ns)");
    }

    /**
     * Unidade de granel da nota.
     *
     * @param unit unidade da nota
     * @return unidade do estoque e fator ({@code size}), ou nulo se não for granel
     */
    private static Size bulk(String unit) {
        return switch (unit.toUpperCase(Locale.ROOT)) {
            case "KG" -> new Size("g", 1000);
            case "G", "GR" -> new Size("g", 1);
            case "L", "LT" -> new Size("ml", 1000);
            case "ML" -> new Size("ml", 1);
            default -> null;
        };
    }

    /**
     * Sugestão de cadastro para um item que não está no estoque ({@code planCreate}).
     *
     * @param item item da nota
     * @return o ingrediente sugerido
     */
    public static CreatePlan planCreate(NfceItem item) {

        // ── 1. Nome com a primeira letra maiúscula, preço e estoque.
        String unit = item.unit().toUpperCase(Locale.ROOT);
        String lower = NfceParser.squash(item.name()).toLowerCase(Locale.ROOT);
        String capitalized = lower.isEmpty() ? lower : lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower
                .substring(1);
        String base = capitalized.length() > 80 ? capitalized.substring(0, 80) : capitalized;
        double price = Js.round(item.unitPrice(), 2);
        double stock = Js.round(item.qty(), 3);

        // ── 2. Unidade: granel (kg/L), tamanho no nome ou unidade.
        if ("KG".equals(unit) || "L".equals(unit) || "LT".equals(unit)) {
            return new CreatePlan(base, "KG".equals(unit) ? "g" : "ml", 1000, price, stock);
        }
        Optional<Size> named = sizeInName(item.name());
        return named.map(size -> new CreatePlan(base, size.unit(), size.size(), price, stock))
                .orElseGet(() -> new CreatePlan(base, "un", 1, price, stock));
    }

    /**
     * Número em texto como o JavaScript.
     *
     * @param number número
     * @return o texto
     */
    private static String text(double number) {
        return NodeInput.jsNumberText(number);
    }

    /**
     * Ingrediente do estoque.
     *
     * @param id          id
     * @param name        nome
     * @param unit        unidade
     * @param packageSize tamanho da embalagem
     * @param packageCost custo da embalagem
     */
    public record Ingredient(long id, String name, String unit, double packageSize, double packageCost) {
    }

    /**
     * Casamento.
     *
     * @param ingredientId ingrediente
     * @param name         nome do ingrediente
     * @param exact        se soma direto (senão o app pergunta)
     */
    public record Match(long ingredientId, String name, boolean exact) {
    }

    /**
     * Unidade e tamanho.
     *
     * @param unit unidade ({@code g} ou {@code ml})
     * @param size tamanho
     */
    public record Size(String unit, double size) {
    }

    /**
     * O que somar.
     *
     * @param addQty      embalagens a somar
     * @param packageCost preço da embalagem
     * @param note        explicação
     */
    public record AddPlan(double addQty, double packageCost, String note) {
    }

    /**
     * O que cadastrar.
     *
     * @param name        nome
     * @param unit        unidade
     * @param packageSize tamanho da embalagem
     * @param packageCost custo da embalagem
     * @param stockQty    estoque inicial
     */
    public record CreatePlan(String name, String unit, double packageSize, double packageCost, double stockQty) {
    }
}
