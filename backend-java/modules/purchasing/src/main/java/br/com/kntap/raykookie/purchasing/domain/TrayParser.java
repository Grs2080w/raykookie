package br.com.kntap.raykookie.purchasing.domain;

import br.com.kntap.raykookie.catalog.api.Packs;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Página de busca das lojas virtuais da plataforma Tray (ex.: Romafele): nome, preço e link de cada produto vêm
 * em atributos {@code data-ga4-*}. Porte da parte pura de {@code backend/lib/scrapers/tray.ts}.
 */
public final class TrayParser {

    /** Início de cada bloco de produto. */
    private static final Pattern PRODUCT = Pattern.compile("<div class=\"product\\b");

    /** Nome do produto. */
    private static final Pattern NAME = Pattern.compile("data-ga4-name=\"([^\"]*)\"");

    /** Preço do produto. */
    private static final Pattern PRICE = Pattern.compile("data-ga4-price=\"([\\d.]+)\"");

    /** Link do produto (o principal). */
    private static final Pattern LINK = Js.pattern("class=\"product-info\"\\s+href=\"([^\"]+)\"");

    /** Link do produto (o da imagem). */
    private static final Pattern IMAGE_LINK = Pattern.compile("<a href=\"([^\"]+)\" class=\"space-image\"");

    /** Produto sem estoque. */
    private static final Pattern UNAVAILABLE = Pattern.compile("indispon[ií]vel|esgotado",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Entidade numérica. */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

    /** Quanto de cada bloco é lido. */
    private static final int HEAD = 6000;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private TrayParser() {
    }

    /**
     * Lê os produtos da página de busca ({@code parseTraySearch}); blocos sem nome, preço ou link ficam de fora.
     *
     * @param html página
     * @return os produtos, sem links repetidos
     */
    public static List<ScrapedProduct> parse(String html) {
        List<ScrapedProduct> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] blocks = PRODUCT.split(html, -1);
        for (int i = 1; i < blocks.length; i++) {
            String head = blocks[i].length() > HEAD ? blocks[i].substring(0, HEAD) : blocks[i];
            product(head).filter(found -> seen.add(found.url())).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Um bloco de produto.
     *
     * @param head começo do bloco
     * @return o produto, se o bloco tiver nome, preço e link
     */
    private static Optional<ScrapedProduct> product(String head) {

        // ── 1. Nome, preço e link.
        String name = NodeInput.jsTrim(decode(first(NAME, head)).replaceAll("[" + Js.SPACES + "]+", " "));
        String rawPrice = first(PRICE, head);
        double price = rawPrice.isEmpty() ? 0 : NodeInput.jsNumber(rawPrice);
        String url = first(LINK, head);
        if (url.isEmpty()) {
            url = first(IMAGE_LINK, head);
        }
        if (name.isEmpty() || !(price > 0) || url.isEmpty()) {
            return Optional.empty();
        }

        // ── 2. Embalagem pelo nome e disponibilidade.
        Optional<Packs.Pack> pack = Packs.parse(name);
        return Optional.of(new ScrapedProduct(name, url, price, !UNAVAILABLE.matcher(head).find(),
                pack.map(Packs.Pack::size).orElse(null), pack.map(Packs.Pack::unit).orElse(null)));
    }

    /**
     * Primeiro grupo do padrão.
     *
     * @param pattern padrão
     * @param text    texto
     * @return o grupo, ou vazio
     */
    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * Entidades HTML dos atributos.
     *
     * @param text texto
     * @return o texto decodificado
     */
    public static String decode(String text) {
        String decoded = text.replace("&amp;", "&").replace("&quot;", "\"").replaceAll("&#0?39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return NUMERIC_ENTITY.matcher(decoded).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1)))));
    }

    /**
     * Fica só com o que tem todas as palavras buscadas ({@code matchesQuery}).
     *
     * @param name nome do produto
     * @param term busca
     * @return verdadeiro se todas as palavras (de 2+ letras, sem o "s" final) aparecem
     */
    public static boolean matchesQuery(String name, String term) {
        String normalized = Js.plain(name);
        for (String word : Js.plain(term).split("[" + Js.SPACES + "]+")) {
            if (word.length() > 1 && !normalized.contains(word.replaceFirst("s$", ""))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Produto da loja.
     *
     * @param name      nome
     * @param url       link
     * @param price     preço
     * @param available se está disponível
     * @param size      tamanho da embalagem (nulo = não está no nome)
     * @param unit      unidade-base (nula = não está no nome)
     */
    public record ScrapedProduct(String name, String url, double price, boolean available, Double size,
            String unit) {
    }
}
