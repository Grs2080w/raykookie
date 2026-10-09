package br.com.kntap.raykookie.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens de sessão no mesmo formato do Node, para que as sessões abertas antes do corte continuem
 * valendo: 32 bytes aleatórios em base64url sem preenchimento; no banco, só o sha256 em hex minúsculo.
 */
public final class SessionTokens {

    /** Cookie da sessão da dona. */
    public static final String OWNER_COOKIE = "rayka_session";

    /** Cookie da sessão do cliente. */
    public static final String CUSTOMER_COOKIE = "rk_customer";

    /** {@code Authorization: Bearer <token>}, como a expressão do Node ({@code /^Bearer\s+(\S+)$/i}). */
    private static final Pattern BEARER = Pattern.compile("^Bearer\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);

    /** Gerador de tokens. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Sem instâncias.
     */
    private SessionTokens() {
    }

    /**
     * Novo token de sessão.
     *
     * @return 32 bytes aleatórios em base64url sem preenchimento (43 caracteres)
     */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash do token, como fica gravado em {@code sessions.token_hash} e {@code customer_sessions.token_hash}.
     *
     * @param token o token
     * @return sha256 em hex minúsculo
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 sempre existe na JVM", impossible);
        }
    }

    /**
     * O token da requisição: primeiro o {@code Authorization: Bearer} (app), depois o cookie (navegador).
     *
     * @param request    a requisição
     * @param cookieName nome do cookie da sessão
     * @return o token, se houver
     */
    public static Optional<String> fromRequest(HttpServletRequest request, String cookieName) {

        // ── 1. O app manda o token no cabeçalho; ele vence o cookie, como no Node.
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            Matcher bearer = BEARER.matcher(authorization.strip());
            if (bearer.matches()) {
                return Optional.of(bearer.group(1));
            }
        }

        // ── 2. O navegador manda o cookie httpOnly.
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals(cookieName))
                .map(Cookie::getValue)
                .filter(value -> !value.isEmpty())
                .findFirst();
    }
}
