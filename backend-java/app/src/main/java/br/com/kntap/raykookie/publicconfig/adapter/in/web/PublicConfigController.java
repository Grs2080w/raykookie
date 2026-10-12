package br.com.kntap.raykookie.publicconfig.adapter.in.web;

import br.com.kntap.raykookie.config.CardPaymentsStatus;
import br.com.kntap.raykookie.config.RaykookieProperties;
import br.com.kntap.raykookie.payments.api.CardBrands;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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

    /** Bandeiras aceitas (ausente se o módulo de pagamentos não está no ar). */
    private final ObjectProvider<CardBrands> brands;

    /** Se os clientes mostram o Status Screen do Mercado Pago depois do pagamento ({@code MP_STATUS_SCREEN}). */
    private final boolean statusScreen;

    /**
     * Cria o controller.
     *
     * @param card         estado do cartão
     * @param properties   configuração
     * @param brands       bandeiras aceitas
     * @param statusScreen {@code MP_STATUS_SCREEN}: {@code false} desliga o Status Screen nos clientes
     */
    public PublicConfigController(CardPaymentsStatus card, RaykookieProperties properties,
            ObjectProvider<CardBrands> brands, @Value("${rayka.mp.status-screen:true}") boolean statusScreen) {
        this.card = card;
        this.properties = properties;
        this.brands = brands;
        this.statusScreen = statusScreen;
    }

    /**
     * Cartão disponível, a chave pública para tokenizar no cliente, as bandeiras aceitas e se o Status Screen do
     * Mercado Pago aparece depois do pagamento.
     *
     * @return {@code cardEnabled}, {@code publicKey}, {@code brands} e {@code statusScreenBrick}
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/payment-config")
    public ResponseEntity<Map<String, Object>> paymentConfig() {

        // ── 1. Cartão disponível e a chave pública para tokenizar no cliente.
        boolean enabled = card.enabled();
        Map<String, Object> json = new HashMap<>();
        json.put("cardEnabled", enabled);
        json.put("publicKey", enabled ? properties.mp().publicKey() : null);

        // ── 2. Bandeiras aceitas e a chave do Status Screen.
        CardBrands source = brands.getIfAvailable();
        List<CardBrands.CardBrand> accepted = enabled && source != null ? source.list() : List.of();
        json.put("brands", accepted);
        json.put("statusScreenBrick", statusScreen);
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
