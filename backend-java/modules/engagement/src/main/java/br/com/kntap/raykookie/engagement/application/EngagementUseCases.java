package br.com.kntap.raykookie.engagement.application;

import br.com.kntap.raykookie.engagement.application.ports.EngagementStore;
import br.com.kntap.raykookie.engagement.domain.EngagementBatch;
import br.com.kntap.raykookie.engagement.domain.EngagementCatalog;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Medição de engajamento: recebe os eventos anônimos dos clientes e responde à dona o que mais chama atenção, que
 * páginas são vistas e até onde as pessoas vão no pedido.
 */
@Service
public class EngagementUseCases {

    /** Período máximo de uma consulta, em dias. */
    static final int MAX_DAYS = 400;

    /** Período padrão, em dias. */
    static final int DEFAULT_DAYS = 30;

    /** Plataformas aceitas no filtro. */
    private static final Set<String> PLATFORMS = Set.of("site", "android", "ios");

    /** Ordenações da lista de receitas. */
    private static final Set<String> SORTS = Set.of("views", "clicks", "adds", "orders", "revenue");

    /** Passos do funil: título, evento e detalhe ({@code *} = o evento todo). */
    private static final List<FunnelStep> FUNNEL = List.of(
            new FunnelStep("Entrou no site ou app", "screen_view", "*"),
            new FunnelStep("Clicou em um item", "menu_item_click", "*"),
            new FunnelStep("Adicionou ao pedido", "cart_add", "*"),
            new FunnelStep("Preencheu os dados", "checkout_step", "data"),
            new FunnelStep("Escolheu a entrega", "checkout_step", "delivery"),
            new FunnelStep("Chegou ao pagamento", "checkout_step", "payment"),
            new FunnelStep("Enviou o pedido", "checkout_submit", "ok"),
            new FunnelStep("Pedido feito", "order_placed", "*"));

    /** Eventos e agregado. */
    private final EngagementStore store;

    /** Limite de lotes por IP. */
    private final RateLimiter limiter;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param store   eventos e agregado
     * @param limiter limite de lotes por IP
     * @param clock   relógio
     */
    public EngagementUseCases(EngagementStore store, RateLimiter limiter, Clock clock) {
        this.store = store;
        this.limiter = limiter;
        this.clock = clock;
    }

    /**
     * Recebe um lote de eventos de um cliente. O IP só entra na conta do limite; não é gravado.
     *
     * @param body     corpo do lote
     * @param clientIp IP real de quem enviou
     * @return quantos eventos entraram
     */
    public int ingest(Map<String, Object> body, String clientIp) {

        // ── 1. Limite por IP.
        if (!limiter.hit(RateLimits.ENGAGEMENT_INGEST, clientIp)) {
            throw new TooManyRequestsException("Muitos envios. Tente mais tarde.");
        }

        // ── 2. Lote conferido (evento ruim é descartado) e gravado.
        EngagementBatch batch = EngagementCatalog.batch(body, clock.instant());
        return batch.events().isEmpty() ? 0 : store.insert(batch);
    }

    /**
     * Resumo do período, com comparação com o período anterior de mesmo tamanho.
     *
     * @param owner    dona
     * @param from     primeiro dia (texto {@code AAAA-MM-DD}, ou nulo)
     * @param to       último dia (ou nulo)
     * @param platform plataforma (ou nulo)
     * @return o resumo
     */
    public Map<String, Object> overview(OwnerId owner, String from, String to, String platform) {

        // ── 1. Período e período anterior.
        Range range = range(from, to);
        String filter = platform(platform);
        Range previous = range.previous();

        // ── 2. Números dos dois períodos.
        Map<String, Object> response = new LinkedHashMap<>(range.json());
        response.put("platform", filter);
        response.put("current", summary(owner, range, filter));
        response.put("previous", summary(owner, previous, filter));
        return response;
    }

    /**
     * Funil do pedido: quantas sessões chegaram a cada passo.
     *
     * @param owner    dona
     * @param from     primeiro dia (ou nulo)
     * @param to       último dia (ou nulo)
     * @param platform plataforma (ou nulo)
     * @return os passos e onde mais se perde
     */
    public Map<String, Object> funnel(OwnerId owner, String from, String to, String platform) {

        // ── 1. Sessões de cada passo.
        Range range = range(from, to);
        String filter = platform(platform);
        Map<String, Long> sessions = sessions(range, filter);

        // ── 2. Cada passo contra o topo e contra o anterior; guarda a maior perda entre passos seguidos.
        List<Map<String, Object>> steps = new ArrayList<>();
        long top = 0;
        long previous = 0;
        String worstAfter = null;
        long worstLost = 0;
        for (int i = 0; i < FUNNEL.size(); i++) {
            FunnelStep step = FUNNEL.get(i);
            long count = sessions.getOrDefault(step.key(), 0L);
            top = i == 0 ? count : top;
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("key", step.key());
            json.put("label", step.label());
            json.put("sessions", count);
            json.put("pct_of_top", pct(count, top));
            json.put("pct_of_previous", i == 0 ? (Object) (count > 0 ? 100.0 : 0.0) : pct(count, previous));
            steps.add(json);
            if (i > 0 && previous - count > worstLost) {
                worstLost = previous - count;
                worstAfter = FUNNEL.get(i - 1).label();
            }
            previous = count;
        }

        // ── 3. Monta a resposta da consulta.
        Map<String, Object> response = new LinkedHashMap<>(range.json());
        response.put("platform", filter);
        response.put("steps", steps);
        response.put("biggest_drop", worstAfter == null ? null : Map.of("after", worstAfter, "lost", worstLost));
        return response;
    }

    /**
     * Receitas e combos: vistas, cliques, adições ao pedido, pedidos e receita.
     *
     * @param owner    dona
     * @param from     primeiro dia (ou nulo)
     * @param to       último dia (ou nulo)
     * @param platform plataforma (ou nulo)
     * @param sort     coluna de ordenação (padrão: cliques)
     * @return a lista
     */
    public Map<String, Object> recipes(OwnerId owner, String from, String to, String platform, String sort) {

        // ── 1. Eventos por item e pedidos por item.
        Range range = range(from, to);
        String filter = platform(platform);
        String order = sort == null || sort.isBlank() ? "clicks" : sort;
        if (!SORTS.contains(order)) {
            throw new InvalidInputException("Ordenação inválida.");
        }
        Map<String, Map<String, Object>> byItem = itemRows(owner, range, filter);

        // ── 2. Nome, taxas e ordenação.
        Map<String, String> names = store.itemNames(owner);
        List<Map<String, Object>> list = new ArrayList<>(byItem.values());
        for (Map<String, Object> item : list) {
            item.put("name", names.getOrDefault((String) item.get("item_id"), "Item removido"));
            item.put("click_rate_pct", pct((Long) item.get("clicks"), (Long) item.get("views")));
            item.put("order_rate_pct", pct((Long) item.get("orders"), (Long) item.get("clicks")));
        }
        list.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(item -> number(item.get(order)))
                .reversed().thenComparing(item -> (String) item.get("name")));

        // ── 3. Monta a resposta da consulta.
        Map<String, Object> response = new LinkedHashMap<>(range.json());
        response.put("platform", filter);
        response.put("sort", order);
        response.put("list", list);
        return response;
    }

    /**
     * Uma linha por item, com os eventos do período e os pedidos feitos.
     *
     * @param owner  dona
     * @param range  período
     * @param filter plataforma ou nulo
     * @return linhas por id de item
     */
    private Map<String, Map<String, Object>> itemRows(OwnerId owner, Range range, String filter) {
        Map<String, Map<String, Object>> byItem = new LinkedHashMap<>();
        for (EngagementStore.ItemRow row : store.items(range.from(), range.to(), filter)) {
            String column = switch (row.name()) {
                case "menu_item_view" -> "views";
                case "menu_item_click" -> "clicks";
                case "cart_add" -> "adds";
                default -> null;
            };
            if (column != null) {
                item(byItem, row.itemId()).put(column, row.sessions());
            }
        }
        for (EngagementStore.SoldItem sold : store.soldItems(owner, range.from(), range.to())) {
            Map<String, Object> item = item(byItem, sold.itemId());
            item.put("orders", sold.orders());
            item.put("revenue", sold.revenue());
        }
        return byItem;
    }

    /**
     * Páginas e telas mais vistas.
     *
     * @param owner    dona
     * @param from     primeiro dia (ou nulo)
     * @param to       último dia (ou nulo)
     * @param platform plataforma (ou nulo)
     * @return a lista
     */
    public Map<String, Object> pages(OwnerId owner, String from, String to, String platform) {

        // ── 1. Período, filtro e as páginas do período.
        Range range = range(from, to);
        String filter = platform(platform);
        List<Map<String, Object>> list = new ArrayList<>();
        for (EngagementStore.PageRow row : store.pages(range.from(), range.to(), filter)) {
            list.add(Map.of("route", row.route(), "views", row.events(), "sessions", row.sessions()));
        }

        // ── 2. Monta a resposta da consulta.
        Map<String, Object> response = new LinkedHashMap<>(range.json());
        response.put("platform", filter);
        response.put("list", list);
        return response;
    }

    /**
     * De onde vêm as entradas e em que plataforma.
     *
     * @param owner    dona
     * @param from     primeiro dia (ou nulo)
     * @param to       último dia (ou nulo)
     * @param platform plataforma (ou nulo)
     * @return origens e plataformas
     */
    public Map<String, Object> sources(OwnerId owner, String from, String to, String platform) {

        // ── 1. Origens: o detalhe de screen_view só vem na entrada da sessão.
        Range range = range(from, to);
        String filter = platform(platform);
        List<Map<String, Object>> referrers = new ArrayList<>();
        for (EngagementStore.Step step : store.steps(range.from(), range.to(), filter)) {
            if ("screen_view".equals(step.name()) && !"*".equals(step.detail())) {
                referrers.add(Map.of("kind", step.detail(), "sessions", step.sessions()));
            }
        }
        referrers.sort(Comparator.<Map<String, Object>, Long>comparing(row -> (Long) row.get("sessions"))
                .reversed());

        // ── 2. Plataformas (sempre todas; o filtro de plataforma não se aplica a elas).
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (EngagementStore.PlatformRow row : store.platforms(range.from(), range.to())) {
            platforms.add(Map.of("platform", row.platform(), "sessions", row.sessions()));
        }
        Map<String, Object> response = new LinkedHashMap<>(range.json());
        response.put("platform", filter);
        response.put("referrers", referrers);
        response.put("platforms", platforms);
        return response;
    }

    /**
     * Números de um período.
     *
     * @param owner  dona
     * @param range  período
     * @param filter plataforma ou nulo
     * @return sessões, pedidos, receita e conversão
     */
    private Map<String, Object> summary(OwnerId owner, Range range, String filter) {

        // ── 1. Sessões por evento e pedidos do período.
        Map<String, Long> sessions = sessions(range, filter);
        EngagementStore.OrderTotals orders = store.orders(owner, range.from(), range.to());
        long visits = sessions.getOrDefault("screen_view|*", 0L);

        // ── 2. Monta o resumo do período.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sessions", visits);
        json.put("page_views", eventsOf(range, filter));
        json.put("orders", orders.orders());
        json.put("paid_orders", orders.paid());
        json.put("revenue", orders.revenue());
        json.put("conversion_pct", pct(sessions.getOrDefault("order_placed|*", 0L), visits));
        return json;
    }

    /**
     * Visualizações de página/tela do período.
     *
     * @param range  período
     * @param filter plataforma ou nulo
     * @return o total
     */
    private long eventsOf(Range range, String filter) {
        return store.steps(range.from(), range.to(), filter).stream()
                .filter(step -> "screen_view".equals(step.name()) && "*".equals(step.detail()))
                .mapToLong(EngagementStore.Step::events).sum();
    }

    /**
     * Sessões por {@code evento|detalhe}.
     *
     * @param range  período
     * @param filter plataforma ou nulo
     * @return as sessões
     */
    private Map<String, Long> sessions(Range range, String filter) {
        Map<String, Long> sessions = new HashMap<>();
        for (EngagementStore.Step step : store.steps(range.from(), range.to(), filter)) {
            sessions.put(step.name() + "|" + step.detail(), step.sessions());
        }
        return sessions;
    }

    /**
     * Linha de um item, criada zerada na primeira vez.
     *
     * @param items linhas por item
     * @param id    item
     * @return a linha
     */
    private static Map<String, Object> item(Map<String, Map<String, Object>> items, String id) {
        return items.computeIfAbsent(id, key -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("item_id", key);
            json.put("views", 0L);
            json.put("clicks", 0L);
            json.put("adds", 0L);
            json.put("orders", 0L);
            json.put("revenue", BigDecimal.ZERO);
            return json;
        });
    }

    /**
     * Valor numérico de uma coluna.
     *
     * @param value valor
     * @return como número
     */
    private static BigDecimal number(Object value) {
        return value instanceof BigDecimal decimal ? decimal : BigDecimal.valueOf((Long) value);
    }

    /**
     * Porcentagem com uma casa, limitada a 100 (os passos não são estritamente aninhados).
     *
     * @param part  parte
     * @param whole todo
     * @return a porcentagem, ou 0 se o todo for 0
     */
    private static double pct(long part, long whole) {
        return whole <= 0 ? 0.0 : Math.min(100.0, Math.round(part * 1000.0 / whole) / 10.0);
    }

    /**
     * Plataforma do filtro.
     *
     * @param platform valor recebido
     * @return a plataforma, ou nulo para todas
     */
    private static String platform(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        if (!PLATFORMS.contains(platform)) {
            throw new InvalidInputException("Plataforma inválida.");
        }
        return platform;
    }

    /**
     * Período da consulta (padrão: últimos 30 dias até hoje, no fuso da loja).
     *
     * @param from primeiro dia (ou nulo)
     * @param to   último dia (ou nulo)
     * @return o período
     */
    private Range range(String from, String to) {
        LocalDate end = parse(to, new StoreClock(clock).today());
        LocalDate start = parse(from, end.minusDays(DEFAULT_DAYS - 1L));
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new InvalidInputException("Período inválido.");
        }
        return new Range(start, end);
    }

    /**
     * Data {@code AAAA-MM-DD}.
     *
     * @param text     texto (ou nulo)
     * @param fallback valor quando vazio
     * @return a data
     */
    private static LocalDate parse(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidInputException("Data inválida: use AAAA-MM-DD.");
        }
    }

    /**
     * Período.
     *
     * @param from primeiro dia
     * @param to   último dia
     */
    private record Range(LocalDate from, LocalDate to) {

        /**
         * Período anterior, do mesmo tamanho e colado neste.
         *
         * @return o período anterior
         */
        Range previous() {
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            return new Range(from.minusDays(days), from.minusDays(1));
        }

        /**
         * Datas para a resposta.
         *
         * @return {@code from} e {@code to}
         */
        Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("from", from.toString());
            json.put("to", to.toString());
            return json;
        }
    }

    /**
     * Passo do funil.
     *
     * @param label  título
     * @param name   evento
     * @param detail detalhe ({@code *} = o evento todo)
     */
    private record FunnelStep(String label, String name, String detail) {

        /**
         * Chave na contagem de sessões.
         *
         * @return {@code evento|detalhe}
         */
        String key() {
            return name + "|" + detail;
        }
    }
}
