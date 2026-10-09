package br.com.kntap.raykookie.purchasing.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura tolerante de um endereço web, como o {@code new URL()} do JavaScript aceita (o {@link java.net.URI}
 * recusa caracteres como {@code |}, comuns nos links de QR code de nota fiscal).
 *
 * @param scheme    esquema em minúsculas
 * @param userInfo  usuário e senha (nulo se não houver)
 * @param host      nome em minúsculas (com colchetes, se for IPv6)
 * @param port      porta (-1 = padrão)
 * @param rest      caminho, consulta e fragmento
 */
public record WebAddress(String scheme, String userInfo, String host, int port, String rest) {

    /** Esquema, autoridade e o resto. */
    private static final Pattern PARTS = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):/*([^/?#\\\\]*)(.*)$",
            Pattern.DOTALL);

    /** Nome com porta opcional. */
    private static final Pattern HOST_PORT = Pattern.compile("^(\\[[0-9a-fA-F:.]+]|[^:\\[\\]]+)(?::(\\d*))?$");

    /** Porta mais alta. */
    private static final int MAX_PORT = 65_535;

    /**
     * Lê o endereço.
     *
     * @param raw texto
     * @return o endereço, ou vazio se não der para ler
     */
    public static Optional<WebAddress> parse(String raw) {

        // ── 1. Esquema, autoridade e o resto.
        Matcher parts = PARTS.matcher(raw.strip());
        if (!parts.matches() || parts.group(2).isEmpty()) {
            return Optional.empty();
        }
        String authority = parts.group(2);
        int at = authority.lastIndexOf('@');
        String userInfo = at < 0 ? null : authority.substring(0, at);

        // ── 2. Nome e porta.
        Matcher hostPort = HOST_PORT.matcher(at < 0 ? authority : authority.substring(at + 1));
        if (!hostPort.matches()) {
            return Optional.empty();
        }
        String port = hostPort.group(2);
        int number = port == null || port.isEmpty() ? -1 : Integer.parseInt(port.length() > 5 ? "99999" : port);
        if (number > MAX_PORT) {
            return Optional.empty();
        }
        return Optional.of(new WebAddress(parts.group(1).toLowerCase(Locale.ROOT), userInfo,
                hostPort.group(1).toLowerCase(Locale.ROOT), number, parts.group(3)));
    }
}
