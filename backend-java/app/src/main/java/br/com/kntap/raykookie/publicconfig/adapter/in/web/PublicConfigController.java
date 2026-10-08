package br.com.kntap.raykookie.publicconfig.adapter.in.web;

import br.com.kntap.raykookie.config.CardPaymentsStatus;
import br.com.kntap.raykookie.config.RaykookieProperties;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuração pública que o site e os apps leem antes de pedir ({@code /api/payment-config}) e antes de
 * entrar ({@code /api/cliente/config}). Só o que é público: a chave pública do Mercado Pago, nunca o token.
 */
@RestController
public class PublicConfigController {

    /** Se o cartão está disponível (G31). */
    private final CardPaymentsStatus card;

    /** Configuração. */
    private final RaykookieProperties properties;

    /**
     * Cria o controller.
     *
     * @param card       estado do cartão
     * @param properties configuração
     */
    public PublicConfigController(CardPaymentsStatus card, RaykookieProperties properties) {
        this.card = card;
        this.properties = properties;
    }

    /**
     * Cartão disponível e a chave pública para tokenizar no cliente.
     *
     * @return {@code cardEnabled} e {@code publicKey}
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/payment-config")
    public ResponseEntity<Map<String, Object>> paymentConfig() {
        boolean enabled = card.enabled();
        Map<String, Object> json = new HashMap<>();
        json.put("cardEnabled", enabled);
        json.put("publicKey", enabled ? properties.mp().publicKey() : null);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=30").body(json);
    }

    /**
     * Configuração da área do cliente.
     *
     * @return {@code googleEnabled}
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/cliente/config")
    public Map<String, Object> customerConfig() {
        RaykookieProperties.Google google = properties.google();
        boolean enabled = google.clientId() != null && !google.clientId().isBlank() && google.clientSecret() != null
                && !google.clientSecret().isBlank();
        return Map.of("googleEnabled", enabled);
    }
}
