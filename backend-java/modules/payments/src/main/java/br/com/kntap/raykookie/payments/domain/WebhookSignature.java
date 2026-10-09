package br.com.kntap.raykookie.payments.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Assinatura das notificações do Mercado Pago ({@code validSignature}): HMAC-SHA256 de
 * {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;}, e (G18) o {@code ts} precisa estar perto de agora.
 */
public final class WebhookSignature {

    /** Acima disto o {@code ts} está em milissegundos. */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private WebhookSignature() {
    }

    /**
     * Confere a assinatura e o horário.
     *
     * @param secret     segredo do webhook
     * @param dataId     {@code data.id} da notificação
     * @param xSignature cabeçalho {@code x-signature} ({@code ts=…,v1=…})
     * @param xRequestId cabeçalho {@code x-request-id} (pode faltar)
     * @param now        agora
     * @param maxSkew    distância máxima entre o {@code ts} e agora
     * @return verdadeiro se a assinatura confere e o {@code ts} está dentro da janela
     */
    public static boolean valid(String secret, String dataId, String xSignature, String xRequestId, Instant now,
            Duration maxSkew) {

        // ── 1. Partes do cabeçalho.
        if (secret == null || secret.isEmpty() || xSignature == null) {
            return false;
        }
        Map<String, String> parts = parts(xSignature);
        String ts = parts.get("ts");
        String v1 = parts.get("v1");
        if (ts == null || ts.isEmpty() || v1 == null || v1.isEmpty() || !fresh(ts, now, maxSkew)) {
            return false;
        }

        // ── 2. HMAC do manifesto, comparado em tempo constante.
        String id = dataId.matches("(?i)[a-z0-9]+") ? dataId.toLowerCase(Locale.ROOT) : dataId;
        String manifest = "id:" + id + ";" + (xRequestId == null || xRequestId.isEmpty() ? ""
                : "request-id:" + xRequestId + ";") + "ts:" + ts + ";";
        byte[] expected = hmac(secret, manifest).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * HMAC-SHA256 em hexadecimal.
     *
     * @param secret  segredo
     * @param message mensagem
     * @return o HMAC
     */
    public static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }

    /**
     * {@code chave=valor} separados por vírgula.
     *
     * @param header cabeçalho
     * @return as partes
     */
    private static Map<String, String> parts(String header) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String pair : header.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                parts.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return parts;
    }

    /**
     * O {@code ts} (segundos ou milissegundos) está a no máximo {@code maxSkew} de agora (G18).
     *
     * @param ts      valor do cabeçalho
     * @param now     agora
     * @param maxSkew distância máxima
     * @return verdadeiro se estiver
     */
    private static boolean fresh(String ts, Instant now, Duration maxSkew) {
        if (!ts.matches("\\d{1,15}")) {
            return false;
        }
        long value = Long.parseLong(ts);
        Instant when = value >= MILLIS_THRESHOLD ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        return Duration.between(when, now).abs().compareTo(maxSkew) <= 0;
    }
}
