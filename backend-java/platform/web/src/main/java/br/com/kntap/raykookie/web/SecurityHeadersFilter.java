package br.com.kntap.raykookie.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabeçalhos de segurança em toda resposta da API, inclusive erros e 404:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} e uma CSP que não deixa a resposta ser
 *       embutida nem executar nada (a API só devolve dados e arquivos);</li>
 *   <li>{@code Referrer-Policy} e {@code Strict-Transport-Security};</li>
 *   <li>{@code Cache-Control: no-store} quando a requisição traz sessão (token ou cookie), para dados da conta não
 *       ficarem em cache do navegador nem de proxy; a rota que quiser outro valor (fotos privadas) o define e vale o
 *       dela;</li>
 *   <li>{@code Secure} em todo cookie definido numa requisição HTTPS, mesmo se {@code COOKIE_SECURE} estiver
 *       desligado.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** A resposta é só dado: não pode ser embutida, nem carregar script ou recurso. */
    static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /** Um ano; os subdomínios também (o navegador ignora o cabeçalho em HTTP puro). */
    static final String HSTS = "max-age=31536000; includeSubDomains";

    /** Prefixo dos cookies de sessão da dona e do cliente. */
    private static final String SESSION_COOKIE_PREFIX = "rayka_";

    /**
     * O filtro também roda no despacho de erro, para o 404 e o 500 saírem com os mesmos cabeçalhos.
     *
     * @return falso
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Põe os cabeçalhos e segue a cadeia com a resposta que mantém {@code Cache-Control} e {@code Set-Cookie} certos.
     *
     * @param request  requisição
     * @param response resposta
     * @param chain    o resto da cadeia
     * @throws ServletException se a cadeia falhar
     * @throws IOException      se a cadeia falhar
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // ── 1. Cabeçalhos fixos.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Strict-Transport-Security", HSTS);

        // ── 2. Quem traz sessão recebe dados da conta: nada de cache, salvo se a rota decidir outro valor.
        if (carriesSession(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }

        // ── 3. Segue a requisição.
        chain.doFilter(request, new Guarded(response, isHttps(request)));
    }

    /**
     * A requisição traz token (Bearer) ou cookie de sessão?
     *
     * @param request requisição
     * @return verdadeiro se traz
     */
    private static boolean carriesSession(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            return true;
        }
        String cookie = request.getHeader(HttpHeaders.COOKIE);
        return cookie != null && cookie.contains(SESSION_COOKIE_PREFIX);
    }

    /**
     * A requisição chegou por HTTPS (direto ou pelo proxy)?
     *
     * @param request requisição
     * @return verdadeiro se sim
     */
    private static boolean isHttps(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && forwarded.split(",")[0].trim().equalsIgnoreCase("https");
    }

    /**
     * Resposta que deixa a rota trocar o {@code Cache-Control} padrão (em vez de somar um segundo cabeçalho) e marca
     * os cookies como {@code Secure} em HTTPS.
     */
    private static final class Guarded extends HttpServletResponseWrapper {

        /** A requisição é HTTPS. */
        private final boolean https;

        /**
         * Cria a resposta.
         *
         * @param response resposta de verdade
         * @param https    se a requisição é HTTPS
         */
        Guarded(HttpServletResponse response, boolean https) {
            super(response);
            this.https = https;
        }

        /** {@inheritDoc} */
        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, adjust(name, value));
        }

        /** {@inheritDoc} */
        @Override
        public void addHeader(String name, String value) {
            if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                super.setHeader(name, value);
                return;
            }
            super.addHeader(name, adjust(name, value));
        }

        /**
         * Acrescenta {@code Secure} ao cookie que ainda não o tem, em HTTPS.
         *
         * @param name  cabeçalho
         * @param value valor
         * @return o valor, ajustado se for cookie
         */
        private String adjust(String name, String value) {
            if (https && value != null && HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)
                    && !value.toLowerCase(Locale.ROOT).contains("; secure")) {
                return value + "; Secure";
            }
            return value;
        }
    }
}
