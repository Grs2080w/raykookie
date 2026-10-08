package br.com.kntap.raykookie.purchasing.adapter.out.http;

import br.com.kntap.raykookie.purchasing.application.ports.SupplierSiteClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Páginas dos sites de fornecedores pela rede ({@code fetchHtml} do Node): endereço conferido a cada
 * redirecionamento (no máximo 3), 1 pedido por segundo por site, tempo limite de 8 s e o texto decodificado no
 * charset que a página declara.
 */
public class HttpSupplierSiteClient implements SupplierSiteClient {

    /** Tempo limite de cada pedido. */
    static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** Redirecionamentos seguidos. */
    private static final int MAX_HOPS = 3;

    /** Intervalo mínimo entre pedidos ao mesmo site, em milissegundos. */
    private static final long GAP_MILLIS = 1000;

    /** Identificação do robô. */
    private static final String USER_AGENT = "RaykookieBot/1.0 (comparador de precos da propria confeitaria)";

    /** Charset no cabeçalho. */
    private static final Pattern HEADER_CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    /** Charset na própria página. */
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)",
            Pattern.CASE_INSENSITIVE);

    /** Charsets latinos. */
    private static final Pattern LATIN = Pattern.compile("iso-8859-1|latin1", Pattern.CASE_INSENSITIVE);

    /** Último pedido a cada site (nanoTime). */
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();

    /** Cliente HTTP (sem seguir redirecionamentos sozinho). */
    private final HttpClient http;

    /** Conferência dos endereços. */
    private final UrlGuard guard;

    /**
     * Cria o cliente.
     *
     * @param http  cliente HTTP
     * @param guard conferência dos endereços
     */
    public HttpSupplierSiteClient(HttpClient http, UrlGuard guard) {
        this.http = http;
        this.guard = guard;
    }

    /** {@inheritDoc} */
    @Override
    public String fetch(String url) {

        // ── 1. Segue até 3 redirecionamentos, conferindo cada endereço.
        HttpResponse<byte[]> response = null;
        String target = url;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            URI uri = checked(target);
            waitTurn(uri.getHost());
            response = send(uri);
            String location = response.statusCode() >= 300 && response.statusCode() < 400
                    ? response.headers().firstValue("location").orElse(null)
                    : null;
            if (location == null) {
                break;
            }
            target = uri.resolve(location).toString();
        }

        // ── 2. Só resposta 2xx vale.
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SiteException("O site respondeu " + (response == null ? "sem resposta" : response.statusCode()));
        }
        return decode(response);
    }

    /**
     * Confere o endereço.
     *
     * @param url endereço
     * @return o endereço conferido
     */
    private URI checked(String url) {
        try {
            return guard.check(url);
        } catch (SiteException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SiteException(e.getMessage());
        }
    }

    /**
     * Um pedido GET.
     *
     * @param uri endereço
     * @return a resposta
     */
    private HttpResponse<byte[]> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", USER_AGENT)
                .header("Accept", "text/html").GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new SiteException("O site não respondeu: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SiteException("Busca interrompida.");
        }
    }

    /**
     * Espera até completar 1 s desde o último pedido ao mesmo site.
     *
     * @param host site
     */
    private void waitTurn(String host) {
        long now = System.nanoTime();
        Long last = lastRequest.put(host, now);
        long waitMillis = last == null ? 0 : GAP_MILLIS - (now - last) / 1_000_000;
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lastRequest.put(host, System.nanoTime());
        }
    }

    /**
     * Texto da página no charset do cabeçalho ou da própria página (latin1 ou UTF-8).
     *
     * @param response resposta
     * @return o HTML
     */
    static String decode(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        Matcher header = HEADER_CHARSET.matcher(response.headers().firstValue("content-type").orElse(""));
        String charset;
        if (header.find()) {
            charset = header.group(1);
        } else {
            Matcher meta = META_CHARSET.matcher(new String(body, 0, Math.min(body.length, 4000),
                    StandardCharsets.ISO_8859_1));
            charset = meta.find() ? meta.group(1) : "utf-8";
        }
        Charset decoder = LATIN.matcher(charset).find() ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
        return new String(body, decoder);
    }
}
