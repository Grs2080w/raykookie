package br.com.kntap.raykookie.web;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Faixas de IP (CIDR) dos proxies em que o servidor confia para informar o IP do cliente no
 * {@code X-Forwarded-For} — em produção, só o proxy reverso local ({@code rproxy}).
 */
public final class TrustedProxies {

    /**
     * IP literal: IPv4 em quatro números, ou IPv6 (sempre com dois-pontos). Nunca um nome, que faria o
     * Java consultar o DNS.
     */
    private static final Pattern IP_LITERAL = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]{1,44}");

    /** As faixas confiáveis. */
    private final List<Cidr> ranges;

    /**
     * Cria a lista a partir de faixas já lidas.
     *
     * @param ranges faixas confiáveis
     */
    private TrustedProxies(List<Cidr> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /**
     * Lê uma lista separada por vírgulas, como em {@code TRUSTED_PROXIES} (ex.:
     * {@code 127.0.0.1/32,::1/128}). Um IP sem {@code /prefixo} vale só ele mesmo.
     *
     * @param text a lista; vazia = nenhum proxy confiável
     * @return as faixas
     * @throws IllegalArgumentException se alguma faixa for inválida (o servidor não sobe)
     */
    public static TrustedProxies parse(String text) {
        List<Cidr> ranges = Arrays.stream(text == null ? new String[0] : text.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(Cidr::parse)
                .toList();
        return new TrustedProxies(ranges);
    }

    /**
     * Converte um IP literal, sem consultar DNS.
     *
     * @param text o IP em texto
     * @return o endereço, ou vazio se o texto não for um IP
     */
    public static Optional<InetAddress> address(String text) {
        if (text == null || !IP_LITERAL.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(text));
        } catch (UnknownHostException notAnAddress) {
            return Optional.empty();
        }
    }

    /**
     * Diz se um endereço pertence a um proxy confiável.
     *
     * @param address o endereço
     * @return verdadeiro se está em alguma faixa
     */
    public boolean contains(InetAddress address) {
        return ranges.stream().anyMatch(range -> range.contains(address));
    }

    /**
     * Uma faixa CIDR.
     *
     * @param network endereço da rede (bytes de IPv4 ou IPv6)
     * @param prefix  quantos bits iniciais precisam ser iguais
     */
    private record Cidr(byte[] network, int prefix) {

        /**
         * Lê uma faixa {@code ip/prefixo} ou um IP sozinho.
         *
         * @param text a faixa
         * @return a faixa
         * @throws IllegalArgumentException se o IP ou o prefixo forem inválidos
         */
        static Cidr parse(String text) {

            // ── 1. Separa o IP do prefixo (sem prefixo = o IP inteiro).
            int slash = text.indexOf('/');
            String ip = slash < 0 ? text : text.substring(0, slash);
            byte[] bytes = address(ip)
                    .orElseThrow(() -> new IllegalArgumentException("Proxy confiável inválido: " + text))
                    .getAddress();
            int bits = bytes.length * 8;

            // ── 2. Lê e confere o prefixo.
            int prefix = slash < 0 ? bits : parsePrefix(text, text.substring(slash + 1));
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Prefixo inválido em proxy confiável: " + text);
            }
            return new Cidr(bytes, prefix);
        }

        /**
         * Lê o número do prefixo.
         *
         * @param text   a faixa inteira (para a mensagem de erro)
         * @param number o texto depois da barra
         * @return o prefixo
         * @throws IllegalArgumentException se não for um número
         */
        private static int parsePrefix(String text, String number) {
            try {
                return Integer.parseInt(number);
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("Prefixo inválido em proxy confiável: " + text, notANumber);
            }
        }

        /**
         * Diz se o endereço está na faixa (IPv4 só casa com faixa IPv4, IPv6 com IPv6).
         *
         * @param address o endereço
         * @return verdadeiro se os primeiros {@code prefix} bits são iguais
         */
        boolean contains(InetAddress address) {
            byte[] other = address.getAddress();
            if (other.length != network.length) {
                return false;
            }
            int shift = network.length * 8 - prefix;
            return new BigInteger(1, other).shiftRight(shift).equals(new BigInteger(1, network).shiftRight(shift));
        }
    }
}
