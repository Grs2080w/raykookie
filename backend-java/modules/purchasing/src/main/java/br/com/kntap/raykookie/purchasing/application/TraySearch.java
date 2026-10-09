package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.purchasing.application.ports.SupplierSiteClient;
import br.com.kntap.raykookie.purchasing.domain.Js;
import br.com.kntap.raykookie.purchasing.domain.TrayParser;
import br.com.kntap.raykookie.purchasing.domain.TrayParser.ScrapedProduct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Busca nas lojas Tray, com cache de 12 h por busca (educação com o site; só desempenho, cada instância tem o
 * seu). Porte de {@code traySearch} e {@code detectScraper} de {@code backend/lib/scrapers/tray.ts}.
 */
@Service
public class TraySearch {

    /** Tempo de vida de uma busca no cache. */
    static final Duration TTL = Duration.ofHours(12);

    /** Sinais de que a página é a busca de uma loja Tray, mesmo sem produtos. */
    private static final Pattern TRAY_PAGE = Pattern.compile("Busca na|palavra_busca|class=\"product");

    /** Sinais de que o site é uma loja Tray. */
    private static final Pattern TRAY_SITE = Pattern.compile("data-tray-theme|Tray Tecnologia",
            Pattern.CASE_INSENSITIVE);

    /** Buscas recentes. */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Acesso aos sites. */
    private final SupplierSiteClient sites;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria o serviço.
     *
     * @param sites acesso aos sites
     * @param clock relógio
     */
    public TraySearch(SupplierSiteClient sites, Clock clock) {
        this.sites = sites;
        this.clock = clock;
    }

    /**
     * Busca o termo na loja e fica só com o que tem todas as palavras.
     *
     * @param baseUrl endereço da loja (ex.: {@code https://www.romafele.com.br})
     * @param term    busca
     * @return os produtos
     * @throws SupplierSiteClient.SiteException se o site falhar ou não parecer uma loja Tray
     */
    public List<ScrapedProduct> search(String baseUrl, String term) {

        // ── 1. Cache por loja e termo.
        String base = baseUrl.replaceAll("/+$", "");
        String key = base + "|" + Js.plain(term);
        Cached hit = cache.get(key);
        Instant now = clock.instant();
        if (hit != null && now.isBefore(hit.at().plus(TTL))) {
            return hit.products();
        }

        // ── 2. Página de busca; sem produtos e sem cara de loja Tray, o site mudou.
        String html = sites.fetch(base + "/loja/busca.php?palavra_busca=" + Js.encodeUriComponent(term));
        List<ScrapedProduct> all = TrayParser.parse(html);
        if (all.isEmpty() && !TRAY_PAGE.matcher(html).find()) {
            throw new SupplierSiteClient.SiteException(
                    "A página não parece ser de uma loja Tray (o site pode ter mudado).");
        }

        // ── 3. Só o que casa com a busca.
        List<ScrapedProduct> products = all.stream().filter(p -> TrayParser.matchesQuery(p.name(), term)).toList();
        cache.put(key, new Cached(now, products));
        return products;
    }

    /**
     * Descobre se o site é uma loja Tray.
     *
     * @param url endereço
     * @return {@code tray}, ou nulo (inclusive se o site não responder)
     */
    public String detect(String url) {
        try {
            return TRAY_SITE.matcher(sites.fetch(url)).find() ? "tray" : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Busca guardada.
     *
     * @param at       quando
     * @param products resultado
     */
    private record Cached(Instant at, List<ScrapedProduct> products) {
    }
}
