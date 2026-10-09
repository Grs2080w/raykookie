package br.com.kntap.raykookie.purchasing.domain;

import br.com.kntap.raykookie.web.NodeInput;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura de NFC-e (cupom fiscal eletrônico): interpreta o HTML da página de consulta aberta pelo QR code.
 * Porte exato de {@code backend/lib/nfce.ts} (parte de leitura); o app baixa a página e manda o HTML.
 */
public final class NfceParser {

    /** Maior página lida (o resto é ignorado). */
    public static final int MAX_PAGE = 3_000_000;

    /** Entidades HTML com nome que o Node conhece. */
    private static final Map<String, String> ENTITIES = entities();

    /** Entidade HTML: {@code &#x41;}, {@code &#65;} ou {@code &nome;}. */
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-f]+|#\\d+|[a-z]+);", Pattern.CASE_INSENSITIVE);

    /** Espaços em sequência. */
    private static final Pattern SPACES = Js.pattern("\\s+");

    /** Qualquer marcação. */
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /** Uma linha de tabela. */
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE);

    /** Os 44 dígitos da chave de acesso. */
    private static final Pattern ACCESS_KEY = Pattern.compile("(?<!\\d)\\d{44}(?!\\d)");

    /** Padrão do texto corrido de um item (plano B, para layouts sem as classes do portal). */
    private static final Pattern TEXT_ITEM = Js
            .pattern("([^\\n]{2,120}?)\\s*(?:\\(\\s*c[oó]digo:?\\s*([^)\\s]+)\\s*\\))?"
            + "\\s*qtde\\.?:?\\s*([\\d.,]+)\\s*(?:un\\.?:?\\s*)?([a-z]{1,4})?\\s*vl\\.?\\s*unit\\.?:?\\s*(?:r\\$)?"
            + "\\s*([\\d.,]+)\\s*(?:vl\\.?\\s*total:?\\s*)?(?:r\\$)?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private NfceParser() {
    }

    /**
     * Entidades com nome.
     *
     * @return nome → texto
     */
    private static Map<String, String> entities() {
        String[] pairs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "ccedil", "ç",
            "Ccedil", "Ç", "atilde", "ã", "Atilde", "Ã", "otilde", "õ", "Otilde", "Õ", "aacute", "á", "Aacute", "Á",
            "eacute", "é", "Eacute", "É", "iacute", "í", "Iacute", "Í", "oacute", "ó", "Oacute", "Ó", "uacute", "ú",
            "Uacute", "Ú", "acirc", "â", "Acirc", "Â", "ecirc", "ê", "Ecirc", "Ê", "ocirc", "ô", "Ocirc", "Ô",
            "agrave", "à", "Agrave", "À", "uuml", "ü", "ordm", "º", "ordf", "ª", "deg", "°"};
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Troca as entidades HTML pelo texto ({@code decodeEntities}).
     *
     * @param text texto com entidades
     * @return o texto
     */
    public static String decodeEntities(String text) {
        return ENTITY.matcher(text).replaceAll(match -> Matcher.quoteReplacement(entity(match.group(), match
                .group(1))));
    }

    /**
     * Uma entidade.
     *
     * @param whole  a entidade inteira
     * @param entity o que está entre {@code &} e {@code ;}
     * @return o texto, ou a própria entidade se for desconhecida
     */
    private static String entity(String whole, String entity) {
        if (entity.charAt(0) != '#') {
            return ENTITIES.getOrDefault(entity, whole);
        }
        boolean hex = Character.toLowerCase(entity.charAt(1)) == 'x';
        try {
            int code = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
            return code > 0 && code < 0x110000 ? Character.toString(code) : whole;
        } catch (NumberFormatException e) {
            return whole;
        }
    }

    /**
     * Entidades decodificadas e espaços simples ({@code squash}).
     *
     * @param text texto
     * @return o texto
     */
    static String squash(String text) {
        return SPACES.matcher(decodeEntities(text)).replaceAll(" ").strip();
    }

    /**
     * Texto sem marcação ({@code stripTags}).
     *
     * @param html HTML
     * @return o texto
     */
    static String stripTags(String html) {
        return squash(TAG.matcher(html).replaceAll(" "));
    }

    /**
     * Número como as notas escrevem ({@code brNumber}): "1.234,56", "4,9900", "4.99".
     *
     * @param value texto
     * @return o número, ou NaN
     */
    public static double brNumber(String value) {
        String text = value == null ? "" : value.replaceAll("[^\\d.,-]", "");
        if (text.isEmpty()) {
            return Double.NaN;
        }
        if (text.contains(",")) {
            return NodeInput.jsNumber(text.replace(".", "").replaceFirst(",", "."));
        }
        long dots = text.chars().filter(c -> c == '.').count();
        return NodeInput.jsNumber(dots > 1 ? text.replace(".", "") : text);
    }

    /**
     * Os 44 dígitos da chave da nota dentro do link do QR code ({@code accessKeyFromText}).
     *
     * @param text link ou texto
     * @return a chave, se houver
     */
    public static Optional<String> accessKeyFromText(String text) {
        Matcher key = ACCESS_KEY.matcher(decodeUriComponent(text).orElse(text));
        return key.find() ? Optional.of(key.group()) : Optional.empty();
    }

    /**
     * {@code decodeURIComponent} do JavaScript ({@code +} continua {@code +}).
     *
     * @param text texto
     * @return o texto decodificado, ou vazio se tiver um {@code %} malformado
     */
    static Optional<String> decodeUriComponent(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            if (i + 2 >= text.length() || !text.substring(i + 1, i + 3).matches("[0-9a-fA-F]{2}")) {
                return Optional.empty();
            }
            out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
            i += 2;
        }
        return Optional.of(out.toString(StandardCharsets.UTF_8));
    }

    /**
     * Só aceita link http(s) de portal do governo ({@code *.gov.br}), que é o que os QR codes trazem.
     *
     * @param raw link
     * @return verdadeiro se for de um portal .gov.br
     */
    public static boolean isNfceUrl(String raw) {
        return WebAddress.parse(raw).filter(address -> "https".equals(address.scheme())
                || "http".equals(address.scheme())).map(WebAddress::host)
                .filter(host -> "gov.br".equals(host) || host.endsWith(".gov.br")).isPresent();
    }

    /**
     * Conteúdo do primeiro elemento com a classe ({@code span(html, cls)}).
     *
     * @param html  HTML
     * @param klass classe (pode ser um trecho de expressão, como {@code txtTit2?})
     * @return o conteúdo, ou nulo
     */
    static String span(String html, String klass) {
        Matcher matcher = Pattern.compile("<(?:span|div|td|label)[^>]*class=[\"'][^\"']*\\b" + klass
                + "\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</(?:span|div|td|label)>", Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Lê a página da nota ({@code parseNfceHtml}).
     *
     * @param html página
     * @param url  link do QR code (pode ser vazio)
     * @return a nota, ou vazio se não houver itens (captcha, erro, outro layout)
     */
    public static Optional<NfceParsed> parse(String html, String url) {

        // ── 1. Itens: o layout padrão do portal, senão o texto corrido; repetidos juntos.
        String page = html.length() > MAX_PAGE ? html.substring(0, MAX_PAGE) : html;
        List<NfceItem> found = itemsFromPortal(page);
        List<NfceItem> items = merge(found.isEmpty() ? itemsFromText(page) : found);
        if (items.isEmpty()) {
            return Optional.empty();
        }

        // ── 2. Chave: do link, do texto da página ou uma impressão digital do conteúdo.
        String store = storeName(page);
        String date = issueDate(page);
        String key = accessKeyFromText(url)
                .or(() -> accessKeyFromText(stripTags(page).replaceAll("(?<=\\d)[" + Js.SPACES + "]+(?=\\d)", "")))
                .orElseGet(() -> fingerprint(store, date, items));
        return Optional.of(new NfceParsed(key, store, date, grandTotal(page), items));
    }

    /**
     * Layout padrão do "Consulta NFC-e" (tabela {@code #tabResult}; SP, PR, MS e vários outros estados).
     *
     * @param html página
     * @return os itens
     */
    static List<NfceItem> itemsFromPortal(String html) {
        List<NfceItem> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group();
            String name = span(row, "txtTit2?");
            if (name != null) {
                portalItem(row, name).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * Um item do layout padrão.
     *
     * @param row  linha da tabela
     * @param name conteúdo do nome
     * @return o item, se quantidade e total forem válidos
     */
    private static Optional<NfceItem> portalItem(String row, String name) {

        // ── 1. Cada campo vem de um elemento com classe própria, depois do rótulo.
        double qty = brNumber(field(row, "Rqtd").replaceFirst("(?i)^.*?qtde\\.?:?", ""));
        String unit = field(row, "RUN").replaceFirst("(?i)^.*?UN:?", "").strip().toUpperCase(Locale.ROOT);
        double unitPrice = brNumber(field(row, "RvlUnit").replaceFirst("(?i)^.*?unit\\.?:?", ""));
        double total = brNumber(field(row, "valor"));
        Matcher code = Js.pattern("\\(?\\s*c[oó]digo:?\\s*([^)<\\s]+)", Pattern.CASE_INSENSITIVE)
                .matcher(field(row, "RCod"));

        // ── 2. Quantidade e total precisam ser números; o preço unitário, se faltar, sai do total.
        if (!Double.isFinite(qty) || !Double.isFinite(total) || qty <= 0) {
            return Optional.empty();
        }
        return Optional.of(new NfceItem(squash(TAG.matcher(name).replaceAll(" ")), code.find() ? code.group(1) : "",
                qty, unit.isEmpty() ? "UN" : unit, Double.isFinite(unitPrice) ? unitPrice : Js.round(total / qty, 4),
                total));
    }

    /**
     * Texto de um campo da linha.
     *
     * @param row   linha
     * @param klass classe do campo
     * @return o texto sem marcação (vazio se não houver)
     */
    private static String field(String row, String klass) {
        String content = span(row, klass);
        return stripTags(content == null ? "" : content);
    }

    /**
     * Plano B para layouts diferentes: o padrão "NOME (Código: 1) Qtde.: 1 UN: UN Vl. Unit.: 4,99 Vl. Total 4,99"
     * no texto corrido ({@code itemsFromText}).
     *
     * @param html página
     * @return os itens
     */
    static List<NfceItem> itemsFromText(String html) {

        // ── 1. Texto com uma linha por bloco.
        String text = html.replaceAll("(?i)<(script|style)[\\s\\S]*?</\\1>", " ")
                .replaceAll("(?i)</?(tr|li|p|div|br|table|tbody|h\\d)[^>]*>", "\n").replaceAll("<[^>]*>", " ");
        text = decodeEntities(text).replaceAll("[ \\t\\u00a0]+", " ");

        // ── 2. Cada ocorrência do padrão vira um item.
        List<NfceItem> out = new ArrayList<>();
        Matcher match = TEXT_ITEM.matcher(text);
        while (match.find()) {
            double qty = brNumber(match.group(3));
            double unitPrice = brNumber(match.group(5));
            double total = brNumber(match.group(6));
            String name = squash(match.group(1)).replaceFirst("(?i)^(item|\\d+)[" + Js.SPACES + "]+", "");
            if (name.isEmpty() || !Double.isFinite(qty) || qty <= 0 || !Double.isFinite(total)) {
                continue;
            }
            String unit = match.group(4) == null ? "UN" : match.group(4).toUpperCase(Locale.ROOT);
            out.add(new NfceItem(name, match.group(2) == null ? "" : match.group(2), qty, unit,
                    Double.isFinite(unitPrice) ? unitPrice : Js.round(total / qty, 4), total));
        }
        return out;
    }

    /**
     * Nome da loja.
     *
     * @param html página
     * @return o nome (até 80)
     */
    static String storeName(String html) {
        String store = span(html, "txtTopo");
        if (store == null) {
            store = span(html, "txtTit");
        }
        String text = stripTags(store == null ? "" : store);
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    /**
     * Data de emissão.
     *
     * @param html página
     * @return {@code AAAA-MM-DD}, ou nulo
     */
    static String issueDate(String html) {
        String text = stripTags(html);
        Matcher match = Js.pattern("emiss[aã]o:?[^0-9]{0,40}(\\d{2})/(\\d{2})/(\\d{4})", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!match.find()) {
            match = Js.pattern("(\\d{2})/(\\d{2})/(\\d{4})\\s+\\d{2}:\\d{2}").matcher(text);
            if (!match.find()) {
                return null;
            }
        }
        return match.group(3) + "-" + match.group(2) + "-" + match.group(1);
    }

    /**
     * Valor total da nota.
     *
     * @param html página
     * @return o total, ou nulo
     */
    static Double grandTotal(String html) {
        String text = stripTags(html);
        Matcher match = Js.pattern("valor\\s+a\\s+pagar\\s*(?:r\\$)?\\s*:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!match.find()) {
            match = Js.pattern("valor\\s+total\\s*(?:r\\$)?\\s*:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            if (!match.find()) {
                return null;
            }
        }
        double total = brNumber(match.group(1));
        return Double.isFinite(total) ? total : null;
    }

    /**
     * Junta itens repetidos (o mesmo produto bipado duas vezes) e descarta nomes vazios ou enormes.
     *
     * @param items itens lidos
     * @return os itens juntados, na ordem da primeira ocorrência
     */
    static List<NfceItem> merge(List<NfceItem> items) {
        Map<String, NfceItem> merged = new LinkedHashMap<>();
        for (NfceItem item : items) {
            if (item.name().isEmpty() || item.name().length() > 160) {
                continue;
            }
            String key = item.code() + "|" + item.name().toLowerCase(Locale.ROOT) + "|" + item.unit() + "|"
                    + NodeInput.jsNumberText(item.unitPrice());
            merged.merge(key, item, (previous, again) -> new NfceItem(previous.name(), previous.code(),
                    Js.round(previous.qty() + again.qty(), 4), previous.unit(), previous.unitPrice(),
                    Js.round(previous.total() + again.total(), 2)));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Impressão digital de uma nota sem chave: {@code "h:" + sha256(JSON.stringify([loja, data, itens]))}, com o
     * mesmo JSON do Node para que a trava de nota repetida reconheça notas lançadas antes do corte.
     *
     * @param store loja
     * @param date  data (ou nulo)
     * @param items itens
     * @return a chave
     */
    static String fingerprint(String store, String date, List<NfceItem> items) {

        // ── 1. O JSON exatamente como o JSON.stringify do Node.
        StringBuilder json = new StringBuilder("[").append(jsonString(store)).append(',')
                .append(date == null ? "null" : jsonString(date)).append(",[");
        for (int i = 0; i < items.size(); i++) {
            NfceItem item = items.get(i);
            json.append(i == 0 ? "" : ",").append('[').append(jsonString(item.name())).append(',')
                    .append(NodeInput.jsNumberText(item.qty())).append(',')
                    .append(NodeInput.jsNumberText(item.total())).append(']');
        }
        json.append("]]");

        // ── 2. sha256 em hexadecimal, 40 primeiros caracteres.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.toString()
                    .getBytes(StandardCharsets.UTF_8));
            return "h:" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /**
     * Texto em JSON como o {@code JSON.stringify} do JavaScript (os nomes vêm de UTF-8 válido, sem surrogates
     * soltos, que o JavaScript também escaparia).
     *
     * @param text texto
     * @return o texto entre aspas, com os escapes do JavaScript
     */
    static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    /**
     * Um item da nota.
     *
     * @param name      nome
     * @param code      código do produto (pode ser vazio)
     * @param qty       quantidade
     * @param unit      unidade da nota ({@code UN}, {@code KG}…)
     * @param unitPrice preço unitário
     * @param total     total do item
     */
    public record NfceItem(String name, String code, double qty, String unit, double unitPrice, double total) {
    }

    /**
     * A nota lida.
     *
     * @param key   chave de acesso (ou impressão digital {@code h:…})
     * @param store loja
     * @param date  data de emissão ({@code AAAA-MM-DD}) ou nulo
     * @param total valor a pagar, ou nulo
     * @param items itens
     */
    public record NfceParsed(String key, String store, String date, Double total, List<NfceItem> items) {
    }
}
