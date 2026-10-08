package br.com.kntap.raykookie.web;

import br.com.kntap.raykookie.web.WebProperties.BodyLimit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Recusa corpos grandes demais <b>antes</b> de lê-los para a memória (G08): pelo {@code Content-Length}
 * declarado, sem ler nada; e, quando o tamanho não é declarado (envio em partes), contando os bytes à
 * medida que a rota lê e parando no primeiro byte acima do limite.
 *
 * <p>Formulários multipart são lidos pelo próprio Tomcat, fora deste contador: neles valem também os
 * limites {@code spring.servlet.multipart.*} (por arquivo e por envio).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Limite das rotas sem regra própria. */
    private final Rule fallback;

    /** Regras por rota, na ordem da configuração (vale a primeira que casar). */
    private final List<Rule> rules;

    /**
     * Monta as regras a partir da configuração.
     *
     * @param properties limites configurados
     */
    public RequestSizeLimitFilter(WebProperties properties) {
        PathPatternParser parser = new PathPatternParser();
        this.fallback = new Rule(null, null, properties.defaultMaxBodyBytes(), properties.defaultMessage());
        this.rules = properties.bodyLimits().stream()
                .map(limit -> Rule.of(parser, limit, properties.defaultMessage()))
                .toList();
    }

    /**
     * Confere o tamanho declarado e embrulha o corpo num contador.
     *
     * @param request  requisição
     * @param response resposta
     * @param chain    o resto da cadeia
     * @throws ServletException se a cadeia falhar
     * @throws IOException      se a escrita da resposta falhar
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // ── 1. Acha o limite desta rota.
        Rule rule = ruleFor(request);

        // ── 2. Tamanho declarado acima do limite: responde 413 sem ler um byte do corpo.
        if (request.getContentLengthLong() > rule.maxBytes()) {
            reject(response, rule.message());
            return;
        }

        // ── 3. Tamanho desconhecido (ou mentiroso): a rota lê por um contador que para no limite.
        chain.doFilter(new LimitedBodyRequest(request, rule), response);
    }

    /**
     * Escolhe a regra da requisição: a primeira por rota que casar, senão a padrão.
     *
     * @param request requisição
     * @return a regra
     */
    private Rule ruleFor(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        return rules.stream()
                .filter(rule -> rule.matches(request.getMethod(), container))
                .findFirst()
                .orElse(fallback);
    }

    /**
     * Responde 413 em JSON e pede ao cliente que feche a conexão (o resto do corpo não será lido).
     *
     * @param response resposta
     * @param message  mensagem do erro
     * @throws IOException se a escrita falhar
     */
    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setHeader("Connection", "close");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(new ErrorBody(message).toJson());
    }

    /**
     * Uma regra de limite já compilada.
     *
     * @param method   método HTTP, ou nulo na regra padrão
     * @param pattern  padrão do caminho, ou nulo na regra padrão
     * @param maxBytes tamanho máximo do corpo
     * @param message  mensagem do 413
     */
    private record Rule(String method, PathPattern pattern, long maxBytes, String message) {

        /**
         * Compila uma regra da configuração.
         *
         * @param parser         analisador de padrões de caminho
         * @param limit          regra configurada
         * @param defaultMessage mensagem usada quando a regra não tem a sua
         * @return a regra compilada
         */
        static Rule of(PathPatternParser parser, BodyLimit limit, String defaultMessage) {
            String message = limit.message() == null ? defaultMessage : limit.message();
            return new Rule(limit.method(), parser.parse(limit.path()), limit.maxBytes(), message);
        }

        /**
         * Diz se a regra vale para a requisição.
         *
         * @param requestMethod método da requisição
         * @param path          caminho da requisição
         * @return verdadeiro se método e caminho casam
         */
        boolean matches(String requestMethod, PathContainer path) {
            return method.equalsIgnoreCase(requestMethod) && pattern.matches(path);
        }
    }

    /**
     * Requisição cujo corpo só pode ser lido até o limite da regra.
     */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        /** Regra que vale para esta requisição. */
        private final Rule rule;

        /** Fluxo limitado, criado no primeiro pedido. */
        private ServletInputStream limited;

        /**
         * Embrulha a requisição.
         *
         * @param request requisição original
         * @param rule    regra de limite
         */
        LimitedBodyRequest(HttpServletRequest request, Rule rule) {
            super(request);
            this.rule = rule;
        }

        /**
         * O corpo, por um contador que lança {@link RequestBodyTooLargeException} ao passar do limite.
         *
         * @return o fluxo limitado
         * @throws IOException se o fluxo original não puder ser aberto
         */
        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream(), rule);
            }
            return limited;
        }

        /**
         * O corpo como texto, lido pelo mesmo contador.
         *
         * @return leitor do corpo
         * @throws IOException se o fluxo não puder ser aberto
         */
        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * Fluxo que conta os bytes lidos e lança ao passar do limite.
     */
    private static final class LimitedInputStream extends ServletInputStream {

        /** Fluxo original do servidor. */
        private final ServletInputStream delegate;

        /** Regra de limite. */
        private final Rule rule;

        /** Bytes lidos até agora. */
        private long count;

        /**
         * Cria o contador.
         *
         * @param delegate fluxo original
         * @param rule     regra de limite
         */
        LimitedInputStream(ServletInputStream delegate, Rule rule) {
            this.delegate = delegate;
            this.rule = rule;
        }

        /**
         * Lê um byte.
         *
         * @return o byte, ou -1 no fim
         * @throws IOException se passar do limite ou a leitura falhar
         */
        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        /**
         * Lê um trecho.
         *
         * @param buffer destino
         * @param offset início no destino
         * @param length máximo a ler
         * @return bytes lidos, ou -1 no fim
         * @throws IOException se passar do limite ou a leitura falhar
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        /**
         * Soma bytes lidos e lança ao passar do limite.
         *
         * @param bytes quantos bytes acabaram de ser lidos
         * @throws RequestBodyTooLargeException se o total passou do limite
         */
        private void count(int bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > rule.maxBytes()) {
                throw new RequestBodyTooLargeException(rule.message());
            }
        }

        /**
         * Diz se o corpo acabou.
         *
         * @return verdadeiro no fim do corpo
         */
        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        /**
         * Diz se há dados prontos para leitura sem bloquear.
         *
         * @return verdadeiro se há dados prontos
         */
        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        /**
         * Registra o ouvinte de leitura assíncrona (repassado ao fluxo original).
         *
         * @param listener ouvinte
         */
        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
