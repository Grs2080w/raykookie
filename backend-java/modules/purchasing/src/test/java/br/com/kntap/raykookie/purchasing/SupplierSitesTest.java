package br.com.kntap.raykookie.purchasing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.purchasing.adapter.out.http.HttpSupplierSiteClient;
import br.com.kntap.raykookie.purchasing.application.TraySearch;
import br.com.kntap.raykookie.purchasing.application.ports.SupplierSiteClient;
import br.com.kntap.raykookie.purchasing.domain.SafeUrl;
import br.com.kntap.raykookie.purchasing.domain.TrayParser;
import br.com.kntap.raykookie.purchasing.domain.TrayParser.ScrapedProduct;
import br.com.kntap.raykookie.testing.ManualClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sites dos fornecedores: SafeUrl (contra SSRF), o leitor da Tray com o fixture do Node, a busca com cache e o
 * cliente HTTP (WireMock).
 */
class SupplierSitesTest {

    /** Servidor falso. */
    private static WireMockServer server;

    /** Página de busca da Romafele (fixture do Node). */
    private static String html;

    /**
     * Sobe o WireMock e lê o fixture.
     *
     * @throws IOException se não ler
     */
    @BeforeAll
    static void start() throws IOException {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        try (InputStream in = SupplierSitesTest.class.getResourceAsStream("/tray-search.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Derruba o WireMock.
     */
    @AfterAll
    static void stop() {
        server.stop();
    }

    /**
     * DNS falso que responde sempre o mesmo IP.
     *
     * @param ip o IP
     * @return o resolvedor
     */
    private static SafeUrl.HostResolver resolvesTo(String ip) {
        return host -> List.of(InetAddress.getByName(ip));
    }

    /**
     * Endereços recusados e aceitos (casos de {@code backend/tests/safe-url.test.ts} e do plano).
     */
    @Test
    @DisplayName("SafeUrl: só https público, na porta 443, conferindo o IP do nome")
    void safeUrl() {

        // ── 1. Sintaxe.
        assertThat(SafeUrl.parse("https://www.romafele.com.br/").getHost()).isEqualTo("www.romafele.com.br");
        assertThat(SafeUrl.parse("romafele.com.br").getScheme()).isEqualTo("https");
        for (String bad : List.of("https://localhost:10005/api", "https://127.0.0.1/", "https://10.0.0.5/",
                "https://[::1]/", "http://loja.com.br", "https://user:pw@loja.com.br", "https://servidor.internal/",
                "https://intranet/", "file:///etc/passwd", "", "https://loja.com.br:8443", "https://a b.com")) {
            assertThatThrownBy(() -> SafeUrl.parse(bad)).as(bad).isInstanceOf(SafeUrl.UnsafeUrlException.class);
        }

        // ── 2. IP para onde o nome aponta.
        for (String ip : List.of("192.168.0.10", "127.0.0.1", "::1", "10.1.2.3", "172.16.0.1", "169.254.1.1",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "fc00::1", "fe80::1", "::")) {
            assertThatThrownBy(() -> SafeUrl.check("https://evil.example.com", resolvesTo(ip))).as(ip)
                    .hasMessage(SafeUrl.NOT_PUBLIC);
        }
        assertThat(SafeUrl.check("https://loja.example.com", resolvesTo("203.0.113.7"))).isInstanceOf(URI.class);
        assertThat(SafeUrl.check("https://loja.example.com", resolvesTo("2001:db8::1"))).isInstanceOf(URI.class);
        assertThatThrownBy(() -> SafeUrl.check("https://metadata.google.internal", resolvesTo("8.8.8.8")))
                .hasMessage(SafeUrl.NOT_PUBLIC);
        assertThatThrownBy(() -> SafeUrl.check("https://naoexiste.example", host -> {
            throw new UnknownHostException(host);
        })).hasMessage("Não encontrei esse site.");
        assertThatThrownBy(() -> SafeUrl.check("https://vazio.example", host -> List.of()))
                .hasMessage(SafeUrl.NOT_PUBLIC);
        assertThat(SafeUrl.origin(SafeUrl.parse("https://WWW.Loja.com.br:443/x?y"))).isEqualTo("https://www.loja.com.br");
    }

    /**
     * Leitor da Tray com o fixture do Node.
     */
    @Test
    @DisplayName("Tray: nome, preço, link e embalagem de cada produto; filtro pelas palavras")
    void trayParser() {
        List<ScrapedProduct> list = TrayParser.parse(html);
        assertThat(list.size()).isGreaterThanOrEqualTo(2);
        ScrapedProduct jazam = list.stream().filter(p -> p.name().contains("JAZAM")).findFirst().orElseThrow();
        assertThat(jazam).isEqualTo(new ScrapedProduct(jazam.name(), "https://www.romafele.com.br/doces/jazam-coloreti-1-kg",
                44.99, true, 1000.0, "g"));
        ScrapedProduct sugar = list.stream().filter(p -> p.name().contains("IMPALPAVEL")).findFirst().orElseThrow();
        assertThat(sugar.price()).isEqualTo(15.5);
        assertThat(sugar.size()).isEqualTo(1000.0);
        ScrapedProduct candle = list.stream().filter(p -> p.name().contains("Vela")).findFirst().orElseThrow();
        assertThat(candle.price()).isEqualTo(7.99);
        assertThat(candle.size()).isNull();
        assertThat(TrayParser.parse("<html><body>nada</body></html>")).isEmpty();
        assertThat(TrayParser.matchesQuery("ACUCAR IMPALPAVEL CONFEITEIRO 1KG", "açúcar impalpável")).isTrue();
        assertThat(TrayParser.matchesQuery("Chocolates granulados", "chocolate")).isTrue();
        assertThat(TrayParser.matchesQuery("Vela Balão Branca", "chocolate")).isFalse();
        assertThat(TrayParser.decode("a&amp;b&quot;&#39;&#039;&lt;&gt;&nbsp;&#65;")).isEqualTo("a&b\"''<> A");
    }

    /**
     * Busca: endereço montado, cache de 12 h e erro de site que mudou.
     */
    @Test
    @DisplayName("TraySearch: monta a URL, guarda 12 h em cache e reconhece site que mudou")
    void traySearch() {

        // ── 1. Busca com acento: codificada como o encodeURIComponent.
        List<String> calls = new ArrayList<>();
        ManualClock clock = new ManualClock(Instant.parse("2026-10-07T12:00:00Z"));
        TraySearch search = new TraySearch(url -> {
            calls.add(url);
            return html.replace("JAZAM COLORETI 1 KG", "JAZAM COLORÉTI 1 KG");
        }, clock);
        assertThat(search.search("https://www.romafele.com.br/", "coloréti")).extracting(ScrapedProduct::name)
                .contains("JAZAM COLORÉTI 1 KG");
        assertThat(calls).containsExactly("https://www.romafele.com.br/loja/busca.php?palavra_busca=color%C3%A9ti");

        // ── 2. Cache: a mesma busca não vai ao site; 12 h depois, vai.
        search.search("https://www.romafele.com.br", "coloréti");
        assertThat(calls).hasSize(1);
        clock.advance(Duration.ofHours(12).plusSeconds(1));
        search.search("https://www.romafele.com.br", "coloréti");
        assertThat(calls).hasSize(2);

        // ── 3. Página que não é de loja Tray; detecção.
        TraySearch broken = new TraySearch(url -> "<html><body>Em manutenção</body></html>", clock);
        assertThatThrownBy(() -> broken.search("https://loja.exemplo.com", "chocolate"))
                .isInstanceOf(SupplierSiteClient.SiteException.class).hasMessageContaining("Tray");
        assertThat(new TraySearch(url -> "<div data-tray-theme=\"x\">", clock).detect("https://x.com")).isEqualTo("tray");
        assertThat(broken.detect("https://x.com")).isNull();
        assertThat(new TraySearch(url -> {
            throw new SupplierSiteClient.SiteException("fora");
        }, clock).detect("https://x.com")).isNull();
    }

    /**
     * Cliente HTTP: charset, redirecionamento conferido, erro de status e endereço recusado.
     */
    @Test
    @DisplayName("cliente HTTP: latin1, redirecionamento conferido, 503 e endereço recusado")
    void httpClient() {

        // ── 1. Página em latin1 e redirecionamento.
        server.stubFor(get(urlEqualTo("/latin")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html; charset=iso-8859-1")
                .withBody("<p>Açúcar</p>".getBytes(StandardCharsets.ISO_8859_1))));
        server.stubFor(get(urlEqualTo("/meta")).willReturn(aResponse().withStatus(200)
                .withBody("<meta charset=\"latin1\"><p>Pão</p>".getBytes(StandardCharsets.ISO_8859_1))));
        server.stubFor(get(urlEqualTo("/vai")).willReturn(aResponse().withStatus(302).withHeader("Location", "/latin")));
        server.stubFor(get(urlEqualTo("/loop")).willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));
        server.stubFor(get(urlPathEqualTo("/fora")).willReturn(aResponse().withStatus(503)));
        List<String> checked = new ArrayList<>();
        HttpSupplierSiteClient client = new HttpSupplierSiteClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), url -> {
                    checked.add(url);
                    return URI.create(url);
                });
        String base = "http://localhost:" + server.port();
        assertThat(client.fetch(base + "/vai")).isEqualTo("<p>Açúcar</p>");
        assertThat(checked).containsExactly(base + "/vai", base + "/latin");
        assertThat(client.fetch(base + "/meta")).contains("Pão");

        // ── 2. Erros: status, redirecionamento sem fim e endereço recusado.
        assertThatThrownBy(() -> client.fetch(base + "/fora")).hasMessage("O site respondeu 503");
        assertThatThrownBy(() -> client.fetch(base + "/loop")).hasMessage("O site respondeu 302");
        HttpSupplierSiteClient guarded = new HttpSupplierSiteClient(HttpClient.newHttpClient(),
                url -> SafeUrl.check(url, resolvesTo("10.0.0.1")));
        assertThatThrownBy(() -> guarded.fetch("https://loja.example.com/")).isInstanceOf(
                SupplierSiteClient.SiteException.class).hasMessage(SafeUrl.NOT_PUBLIC);
        HttpSupplierSiteClient down = new HttpSupplierSiteClient(HttpClient.newHttpClient(), URI::create);
        assertThatThrownBy(() -> down.fetch("http://localhost:1/")).isInstanceOf(SupplierSiteClient.SiteException.class)
                .hasMessageStartingWith("O site não respondeu");
    }
}
