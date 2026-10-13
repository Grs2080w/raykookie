package br.com.kntap.raykookie.engagement.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A lista fechada de eventos de medição e a conferência de cada lote. Só passa o que está aqui, com as chaves
 * esperadas: nome desconhecido, valor fora da lista ou texto livre são descartados, então nada de pessoal (um
 * código de cupom digitado, por exemplo) consegue entrar.
 *
 * <p>O catálogo é o mesmo do site (TypeScript) e dos apps (Dart); os três são comparados por um teste.
 */
public final class EngagementCatalog {

    /** Tamanho máximo de um lote. */
    public static final int MAX_BATCH = 20;

    /** Diferença máxima entre a hora do evento e a do servidor. */
    static final Duration MAX_SKEW = Duration.ofHours(24);

    /** Plataformas. */
    static final Set<String> PLATFORMS = Set.of("site", "android", "ios");

    /** Versão do app: letras, números e separadores comuns. */
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{0,20}");

    /** Item do cardápio: o id da receita ou {@code combo-<id>}. */
    private static final Pattern ITEM = Pattern.compile("(combo-)?[0-9]{1,9}");

    /** Rota: caminho simples, sem parâmetros de consulta. */
    private static final Pattern ROUTE = Pattern.compile("/[A-Za-z0-9/_:.-]{0,78}");

    /** Trecho do caminho que é só número (id de pedido, por exemplo). */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/[0-9]+(?=/|$)");

    /** Origens de entrada. */
    private static final Set<String> REFERRERS = Set.of("direct", "instagram", "whatsapp", "search", "other");

    /** Onde o item apareceu. */
    private static final Set<String> SURFACES = Set.of("landing", "cardapio", "home_app");

    /** Passos do pedido. */
    private static final Set<String> STEPS = Set.of("items", "data", "delivery", "payment", "extras");

    /** Formas de pagamento. */
    private static final Set<String> METHODS = Set.of("pix", "cartao", "dinheiro");

    /** Resultado de um cupom (nunca o código). */
    private static final Set<String> COUPONS = Set.of("ok", "invalid", "min_total", "inactive", "used_up");

    /** Resultado do envio do pedido. */
    private static final Set<String> SUBMITS = Set.of("ok", "validation", "card_refused", "network", "other");

    /** Resultado do desafio 3DS. */
    private static final Set<String> THREE_DS = Set.of("authenticated", "closed", "failed");

    /** Regras de cada evento. */
    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("screen_view", new Rule(true, false, false, REFERRERS, false)),
            Map.entry("menu_item_view", new Rule(false, true, false, SURFACES, true)),
            Map.entry("menu_item_click", new Rule(false, true, false, SURFACES, true)),
            Map.entry("cart_add", new Rule(false, true, true, Set.of(), false)),
            Map.entry("cart_remove", new Rule(false, true, true, Set.of(), false)),
            Map.entry("checkout_step", new Rule(false, false, false, STEPS, true)),
            Map.entry("payment_method_select", new Rule(false, false, false, METHODS, true)),
            Map.entry("coupon_attempt", new Rule(false, false, false, COUPONS, true)),
            Map.entry("checkout_submit", new Rule(false, false, false, SUBMITS, true)),
            Map.entry("card_3ds", new Rule(false, false, false, THREE_DS, true)),
            Map.entry("order_placed", new Rule(false, false, false, METHODS, true)));

    /**
     * Sem instâncias: só regras.
     */
    private EngagementCatalog() {
    }

    /**
     * Nomes de evento aceitos (para o teste de paridade com o site e os apps).
     *
     * @return os nomes
     */
    public static Set<String> names() {
        return RULES.keySet();
    }

    /**
     * O catálogo como dados (para compará-lo com o do site e dos apps): por evento, se usa rota, item e quantidade,
     * se o detalhe é obrigatório e quais detalhes aceita (em ordem alfabética).
     *
     * @return {@code route}, {@code item}, {@code qty}, {@code detail_required} e {@code details} de cada evento
     */
    public static Map<String, Map<String, Object>> describe() {
        Map<String, Map<String, Object>> events = new TreeMap<>();
        RULES.forEach((name, rule) -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("route", rule.route());
            json.put("item", rule.item());
            json.put("qty", rule.qty());
            json.put("detail_required", rule.detailRequired());
            json.put("details", new TreeSet<>(rule.details()).stream().toList());
            events.put(name, json);
        });
        return events;
    }

    /**
     * Confere um lote. O formato do lote tem que estar certo; cada evento ruim é só descartado.
     *
     * @param body corpo JSON: {@code session_id}, {@code platform}, {@code app_version} e {@code events}
     * @param now  agora
     * @return o lote com os eventos válidos
     * @throws InvalidInputException se o formato do lote estiver errado
     */
    public static EngagementBatch batch(Map<String, Object> body, Instant now) {

        // ── 1. Cabeçalho do lote.
        UUID session = uuid(body.get("session_id"));
        String platform = body.get("platform") instanceof String text ? text : "";
        if (!PLATFORMS.contains(platform)) {
            throw new InvalidInputException("Plataforma inválida.");
        }
        String version = body.get("app_version") instanceof String text ? text : "";
        if (!VERSION.matcher(version).matches()) {
            version = "";
        }

        // ── 2. Lista de eventos, no máximo MAX_BATCH.
        if (!(body.get("events") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_BATCH) {
            throw new InvalidInputException("Lote de eventos inválido.");
        }

        // ── 3. Cada evento: o que não passa é descartado.
        List<EngagementEvent> events = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                event(map, now).ifPresent(events::add);
            }
        }
        return new EngagementBatch(session, platform, version, List.copyOf(events));
    }

    /**
     * Confere um evento.
     *
     * @param map evento como veio
     * @param now agora
     * @return o evento limpo, ou vazio se não passou
     */
    static Optional<EngagementEvent> event(Map<?, ?> map, Instant now) {

        // ── 1. Nome conhecido e hora plausível.
        Rule rule = map.get("name") instanceof String name ? RULES.get(name) : null;
        Instant at = at(map.get("at"));
        if (rule == null || at == null || Duration.between(at, now).abs().compareTo(MAX_SKEW) > 0) {
            return Optional.empty();
        }
        String name = (String) map.get("name");

        // ── 2. Cada campo só existe se o evento o usa, e vem da lista ou do formato.
        String route = rule.route() ? route(map.get("route")) : "";
        String item = rule.item() ? string(map.get("item_id"), ITEM) : "";
        String detail = string(map.get("detail"), rule.details());
        int qty = rule.qty() ? quantity(map.get("qty")) : 0;

        // ── 3. O que o evento exige.
        boolean missing = rule.route() && route.isEmpty() || rule.item() && item.isEmpty()
                || rule.qty() && qty == 0 || rule.detailRequired() && detail.isEmpty();
        return missing ? Optional.empty() : Optional.of(new EngagementEvent(at, name, route, item, detail, qty));
    }

    /**
     * Rota sem parâmetros: tira o que vem depois de {@code ?} ou {@code #} e troca ids numéricos por {@code :id}.
     *
     * @param value valor como veio
     * @return a rota limpa, ou vazio se não for um caminho simples
     */
    static String route(Object value) {
        if (!(value instanceof String text)) {
            return "";
        }
        String path = text.split("[?#]", 2)[0];
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        path = NUMERIC_SEGMENT.matcher(path).replaceAll("/:id");
        return ROUTE.matcher(path).matches() ? path : "";
    }

    /**
     * Texto que casa com um formato.
     *
     * @param value   valor como veio
     * @param pattern formato
     * @return o texto, ou vazio
     */
    private static String string(Object value, Pattern pattern) {
        return value instanceof String text && pattern.matcher(text).matches() ? text : "";
    }

    /**
     * Texto que está numa lista.
     *
     * @param value   valor como veio
     * @param allowed valores aceitos
     * @return o texto, ou vazio
     */
    private static String string(Object value, Set<String> allowed) {
        return value instanceof String text && allowed.contains(text) ? text : "";
    }

    /**
     * Quantidade de 1 a 99.
     *
     * @param value valor como veio
     * @return a quantidade, ou 0
     */
    private static int quantity(Object value) {
        return value instanceof Number number && number.doubleValue() >= 1 && number.doubleValue() <= 99
                ? number.intValue() : 0;
    }

    /**
     * Hora do evento em milissegundos desde 1970.
     *
     * @param value valor como veio
     * @return o instante, ou nulo
     */
    private static Instant at(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? Instant.ofEpochMilli(number.longValue()) : null;
    }

    /**
     * Código da visita.
     *
     * @param value valor como veio
     * @return o UUID
     * @throws InvalidInputException se não for um UUID
     */
    private static UUID uuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Sessão inválida.");
        }
    }

    /**
     * O que um evento usa.
     *
     * @param route          usa rota (e a exige)
     * @param item           usa item do cardápio (e o exige)
     * @param qty            usa quantidade (e a exige)
     * @param details        valores aceitos em {@code detail}
     * @param detailRequired {@code detail} é obrigatório
     */
    private record Rule(boolean route, boolean item, boolean qty, Set<String> details, boolean detailRequired) {
    }
}
