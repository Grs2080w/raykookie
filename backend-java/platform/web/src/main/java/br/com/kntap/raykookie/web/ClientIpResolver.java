package br.com.kntap.raykookie.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Descobre o IP real do cliente, usado nos limites de tentativa (G05). No Node, a chave era o
 * {@code X-Forwarded-For} cru: o cliente escolhia o próprio "IP" e escapava do limite.
 *
 * <p>Regra: o cabeçalho só é lido se a conexão veio de um proxy confiável. Nesse caso ele é percorrido
 * da direita para a esquerda (cada proxy acrescenta à direita o IP de quem falou com ele), pulando os
 * proxies confiáveis; o primeiro IP que não é de proxy confiável é o cliente. O que o cliente escreveu
 * no cabeçalho fica à esquerda disso e nunca é usado.
 */
public final class ClientIpResolver {

    /** Nome do cabeçalho. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    /** Proxies confiáveis. */
    private final TrustedProxies trusted;

    /**
     * Cria o resolvedor.
     *
     * @param trusted proxies confiáveis ({@code TRUSTED_PROXIES})
     */
    public ClientIpResolver(TrustedProxies trusted) {
        this.trusted = trusted;
    }

    /**
     * IP do cliente de uma requisição (junta todas as linhas de {@code X-Forwarded-For}, na ordem).
     *
     * @param request a requisição
     * @return o IP do cliente
     */
    public String resolve(HttpServletRequest request) {
        List<String> lines = Collections.list(request.getHeaders(FORWARDED_FOR));
        String header = lines.isEmpty() ? null : String.join(",", lines);
        return resolve(request.getRemoteAddr(), header);
    }

    /**
     * IP do cliente a partir do par de quem conectou e do cabeçalho.
     *
     * @param remoteAddr    IP de quem abriu a conexão com o servidor
     * @param xForwardedFor valor do {@code X-Forwarded-For} (pode ser nulo)
     * @return o IP do cliente, normalizado
     */
    public String resolve(String remoteAddr, String xForwardedFor) {

        // ── 1. Conexão que não veio de proxy confiável: o cabeçalho é do cliente e não vale nada.
        Optional<InetAddress> peer = TrustedProxies.address(remoteAddr);
        if (peer.isEmpty() || !trusted.contains(peer.get()) || xForwardedFor == null) {
            return peer.map(InetAddress::getHostAddress).orElse(String.valueOf(remoteAddr));
        }

        // ── 2. Da direita para a esquerda, pula os proxies confiáveis.
        String[] hops = xForwardedFor.split(",");
        InetAddress last = peer.get();
        for (int i = hops.length - 1; i >= 0; i--) {
            Optional<InetAddress> hop = TrustedProxies.address(hops[i].trim());
            if (hop.isEmpty()) {
                return last.getHostAddress();
            }
            if (!trusted.contains(hop.get())) {
                return hop.get().getHostAddress();
            }
            last = hop.get();
        }

        // ── 3. Todos confiáveis: o cliente é o mais à esquerda (tráfego interno).
        return last.getHostAddress();
    }
}
