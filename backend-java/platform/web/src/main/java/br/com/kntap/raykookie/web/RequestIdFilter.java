package br.com.kntap.raykookie.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dá a cada requisição um identificador ({@code requestId}) que aparece em todo log dela (MDC), no
 * cabeçalho {@code X-Request-Id} da resposta e nos alertas. Assim um erro 500 visto pelo cliente pode
 * ser achado no log.
 *
 * <p>Se a requisição já trouxer um {@code X-Request-Id} curto e só com caracteres seguros (o Mercado
 * Pago manda um em cada notificação), ele é mantido; senão, um novo é gerado.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Nome do cabeçalho, na requisição e na resposta. */
    public static final String HEADER = "X-Request-Id";

    /** Chave do identificador no MDC (e nos logs JSON). */
    public static final String MDC_KEY = "requestId";

    /** Atributo da requisição que guarda o identificador. */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    /** Identificador aceito vindo de fora: nada que possa forjar linhas ou campos de log. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    /**
     * O identificador da requisição em andamento nesta thread.
     *
     * @return o identificador, ou vazio fora de uma requisição
     */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /**
     * Define o identificador, roda o resto da cadeia e limpa o MDC no fim.
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

        // ── 1. Mantém o identificador recebido se for seguro; senão, cria um.
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        // ── 2. Publica no log, na resposta e na requisição (para o despacho de erro reaproveitar).
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);

        // ── 3. Segue a requisição com o identificador no MDC.
        runWithId(id, request, response, chain);
    }

    /**
     * O despacho interno para {@code /error} acontece depois que a requisição original saiu deste
     * filtro: devolve o mesmo identificador ao MDC, para o log do erro sair com ele.
     *
     * @return falso: o filtro também roda no despacho de erro
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Despacho de erro: reaproveita o identificador da requisição original.
     *
     * @param request  requisição
     * @param response resposta
     * @param chain    o resto da cadeia
     * @throws ServletException se a cadeia falhar
     * @throws IOException      se a cadeia falhar
     */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Object id = request.getAttribute(ATTRIBUTE);
        if (id == null) {
            chain.doFilter(request, response);
            return;
        }
        runWithId(id.toString(), request, response, chain);
    }

    /**
     * Roda o resto da cadeia com o identificador no MDC e o limpa no fim, porque a thread pode atender
     * outra requisição depois.
     *
     * @param id       identificador da requisição
     * @param request  requisição
     * @param response resposta
     * @param chain    o resto da cadeia
     * @throws ServletException se a cadeia falhar
     * @throws IOException      se a cadeia falhar
     */
    private static void runWithId(String id, HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
