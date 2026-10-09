package br.com.kntap.raykookie.releases.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cabeçalho {@code Range} do download ({@code bytes=início-fim} ou {@code bytes=-últimos}).
 *
 * @param start primeiro byte
 * @param end   último byte (inclusive)
 */
public record ByteRange(long start, long end) {

    /** Formato aceito. */
    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    /**
     * Lê o cabeçalho.
     *
     * @param header valor (pode ser nulo)
     * @param size   tamanho do arquivo
     * @return o trecho pedido; o arquivo todo se não houver cabeçalho válido; vazio se o trecho não existe (416)
     */
    public static Optional<ByteRange> of(String header, long size) {
        Matcher m = RANGE.matcher(header == null ? "" : header);
        if (!m.matches() || m.group(1).isEmpty() && m.group(2).isEmpty()) {
            return Optional.of(new ByteRange(0, size - 1));
        }
        long start;
        long end = size - 1;
        if (!m.group(1).isEmpty()) {
            start = parse(m.group(1));
            if (!m.group(2).isEmpty()) {
                end = Math.min(parse(m.group(2)), size - 1);
            }
        } else {
            start = Math.max(0, size - parse(m.group(2)));
        }
        return start > end || start >= size ? Optional.empty() : Optional.of(new ByteRange(start, end));
    }

    /**
     * Número do cabeçalho (enorme vira o máximo).
     *
     * @param digits dígitos
     * @return o número
     */
    private static long parse(String digits) {
        return digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
    }

    /**
     * Quantos bytes.
     *
     * @return o tamanho do trecho
     */
    public long length() {
        return end - start + 1;
    }
}
