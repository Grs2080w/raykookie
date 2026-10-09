package br.com.kntap.raykookie.purchasing.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Endereços de fornecedores contra SSRF (porte de {@code backend/lib/scrapers/safe-url.ts}): só https, só site
 * público pelo nome e pelo IP para onde o nome aponta, e (mudança no Java) só na porta 443.
 */
public final class SafeUrl {

    /** Mensagem de endereço que não é de site público. */
    public static final String NOT_PUBLIC = "Use o endereço de um site público.";

    /** Domínios internos. */
    private static final Pattern INTERNAL = Pattern.compile(".*\\.(local|internal|localhost|lan|home|corp)$");

    /** IPv4 escrito como número. */
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Nome de site aceito (letras, dígitos, ponto e hífen; IPv6 entre colchetes passa para ser recusado). */
    private static final Pattern VALID_HOST = Pattern.compile("[a-z0-9.-]+|\\[.*]");

    /** Esquema no começo do endereço. */
    private static final Pattern HAS_SCHEME = Pattern.compile("(?i)^https?://.*");

    /** Porta do https. */
    private static final int HTTPS_PORT = 443;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private SafeUrl() {
    }

    /**
     * Confere a sintaxe ({@code parsePublicUrl}): https, com nome de domínio (não IP, não localhost, não .local).
     *
     * @param raw endereço digitado (sem esquema vira https)
     * @return o endereço
     * @throws UnsafeUrlException com a mensagem para a dona
     */
    public static URI parse(String raw) {

        // ── 1. Lê o endereço; sem esquema, completa com https.
        String text = raw.strip();
        WebAddress address = WebAddress.parse(HAS_SCHEME.matcher(text).matches() ? text : "https://" + text)
                .orElseThrow(() -> new UnsafeUrlException("Endereço inválido."));

        // ── 2. Só https, sem usuário e senha, com um nome válido.
        if (!"https".equals(address.scheme())) {
            throw new UnsafeUrlException("Use o endereço do site com https.");
        }
        if (address.userInfo() != null || !VALID_HOST.matcher(address.host()).matches()) {
            throw new UnsafeUrlException("Endereço inválido.");
        }

        // ── 3. Nome público, na porta 443.
        String host = address.host();
        if (IPV4.matcher(host).matches() || host.startsWith("[") || "localhost".equals(host) || !host.contains(".")
                || INTERNAL.matcher(host).matches() || (address.port() != -1 && address.port() != HTTPS_PORT)) {
            throw new UnsafeUrlException(NOT_PUBLIC);
        }
        return toUri(host, address.rest());
    }

    /**
     * O endereço completo; se o caminho tiver caracteres que o {@link URI} não aceita, só a origem.
     *
     * @param host nome
     * @param rest caminho e consulta
     * @return o endereço
     */
    private static URI toUri(String host, String rest) {
        try {
            return new URI("https://" + host + rest);
        } catch (URISyntaxException e) {
            return URI.create("https://" + host);
        }
    }

    /**
     * Confere também os IPs para onde o nome aponta ({@code assertPublicUrl}).
     *
     * @param raw      endereço
     * @param resolver DNS
     * @return o endereço
     * @throws UnsafeUrlException com a mensagem para a dona
     */
    public static URI check(String raw, HostResolver resolver) {
        URI uri = parse(raw);
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("Não encontrei esse site.");
        }
        if (addresses.isEmpty() || addresses.stream().anyMatch(SafeUrl::isPrivate)) {
            throw new UnsafeUrlException(NOT_PUBLIC);
        }
        return uri;
    }

    /**
     * Origem do endereço ({@code URL.origin}): esquema e host, sem caminho.
     *
     * @param uri endereço já conferido
     * @return por exemplo {@code https://www.romafele.com.br}
     */
    public static String origin(URI uri) {
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * IP de rede privada, interna ou especial.
     *
     * @param address endereço
     * @return verdadeiro se não for público
     */
    static boolean isPrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127) || a >= 224;
        }
        return address instanceof Inet6Address && isPrivateV6(bytes);
    }

    /**
     * IPv6 de loopback, não especificado, local único ({@code fc00::/7}), link-local ({@code fe80::/10}) ou
     * IPv4 mapeado ({@code ::ffff:0:0/96}).
     *
     * @param bytes os 16 bytes
     * @return verdadeiro se não for público
     */
    private static boolean isPrivateV6(byte[] bytes) {
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            zeroPrefix &= bytes[i] == 0;
        }
        boolean mapped = zeroPrefix && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        boolean loopbackOrAny = zeroPrefix && bytes[10] == 0 && bytes[11] == 0 && bytes[12] == 0 && bytes[13] == 0
                && bytes[14] == 0 && (bytes[15] & 0xff) <= 1;
        int first = bytes[0] & 0xff;
        return mapped || loopbackOrAny || (first & 0xfe) == 0xfc || (first == 0xfe && (bytes[1] & 0xc0) == 0x80);
    }

    /**
     * DNS (trocável nos testes).
     */
    @FunctionalInterface
    public interface HostResolver {

        /**
         * Todos os IPs do nome.
         *
         * @param host nome
         * @return os IPs
         * @throws UnknownHostException se o nome não existir
         */
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /**
     * Endereço recusado, com a mensagem para a dona.
     */
    public static class UnsafeUrlException extends RuntimeException {

        /** Versão da serialização. */
        private static final long serialVersionUID = 1L;

        /**
         * Cria o erro.
         *
         * @param message mensagem em português
         */
        public UnsafeUrlException(String message) {
            super(message);
        }
    }
}
