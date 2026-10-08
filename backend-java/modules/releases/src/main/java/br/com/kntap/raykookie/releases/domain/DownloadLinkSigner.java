package br.com.kntap.raykookie.releases.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Link de download temporário ({@code signDownload}/{@code verifyDownload}): {@code <exp>.<HMAC-SHA256(segredo,
 * "<app>.<exp>")>} em base64url sem padding. O segredo vem só de {@code DOWNLOAD_SECRET} (G14).
 */
public final class DownloadLinkSigner {

    /** Validade padrão do link, em segundos. */
    public static final long TTL_SECONDS = 300;

    /** Segredo. */
    private final byte[] secret;

    /**
     * Cria o assinador.
     *
     * @param secret {@code DOWNLOAD_SECRET}
     */
    public DownloadLinkSigner(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Assina o link de um app.
     *
     * @param app app
     * @param exp validade (segundos Unix)
     * @return {@code <exp>.<assinatura>}
     */
    public String sign(String app, long exp) {
        return exp + "." + mac(app, exp);
    }

    /**
     * Confere o link.
     *
     * @param app   app
     * @param token token ({@code <exp>.<assinatura>})
     * @param now   agora
     * @return verdadeiro se é deste app e ainda vale
     */
    public boolean verify(String app, String token, Instant now) {

        // ── 1. Formato {@code <exp>.<assinatura>} e prazo.
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot < 1 || !token.substring(0, dot).matches("\\d{1,12}")) {
            return false;
        }
        long exp = Long.parseLong(token.substring(0, dot));
        String sig = token.substring(dot + 1);
        if (sig.isEmpty() || exp < now.getEpochSecond()) {
            return false;
        }

        // ── 2. Assinatura comparada em tempo constante.
        return MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                mac(app, exp).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * HMAC-SHA256 em base64url sem padding.
     *
     * @param app app
     * @param exp validade
     * @return a assinatura
     */
    String mac(String app, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((app + "." + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }
}
