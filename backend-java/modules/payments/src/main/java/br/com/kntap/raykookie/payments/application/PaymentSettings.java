package br.com.kntap.raykookie.payments.application;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuração de pagamentos (as variáveis do {@code .env}, já conferidas pelo app na subida).
 */
@Component
public class PaymentSettings {

    /** Token de acesso do Mercado Pago. */
    private final String accessToken;

    /** Segredo das notificações. */
    private final String webhookSecret;

    /** Segredo das notificações do modo de teste (vazio = não aceita notificações de teste). */
    private final String webhookTestSecret;

    /** Distância máxima do {@code ts} da notificação (G18). */
    private final Duration webhookMaxSkew;

    /** Validade do Pix. */
    private final Duration pixValidity;

    /** Endereço público do site. */
    private final String siteUrl;

    /** E-mail do pagador no link de pagamento. */
    private final String payerEmail;

    /**
     * Lê a configuração.
     *
     * @param accessToken    {@code MP_ACCESS_TOKEN}
     * @param webhookSecret  {@code MP_WEBHOOK_SECRET}
     * @param webhookTestSecret {@code MP_WEBHOOK_SECRET_TEST}
     * @param webhookMaxSkew {@code MP_WEBHOOK_MAX_SKEW}
     * @param pixMinutes     {@code PIX_EXPIRES_MINUTES}
     * @param siteUrl        {@code SITE_URL}
     * @param payerEmail     {@code PAY_LINK_PAYER_EMAIL}
     */
    @Autowired
    public PaymentSettings(@Value("${rayka.mp.access-token:}") String accessToken,
            @Value("${rayka.mp.webhook-secret:}") String webhookSecret,
            @Value("${rayka.mp.webhook-secret-test:}") String webhookTestSecret,
            @Value("${rayka.mp.webhook-max-skew:10m}") Duration webhookMaxSkew,
            @Value("${rayka.pix-expires-minutes:60}") int pixMinutes,
            @Value("${rayka.site-url:https://raykookie.com.br}") String siteUrl,
            @Value("${rayka.pay-link-payer-email:}") String payerEmail) {

        // ── 1. Mercado Pago: token e segredos das notificações (produção e teste).
        this.accessToken = accessToken;
        this.webhookSecret = webhookSecret;
        this.webhookTestSecret = webhookTestSecret;
        this.webhookMaxSkew = webhookMaxSkew;

        // ── 2. Pix, site e e-mail do pagador do link.
        this.pixValidity = Duration.ofMinutes(Math.min(Math.max(pixMinutes, 31), 30 * 24 * 60));
        this.siteUrl = siteUrl.replaceAll("/+$", "");
        this.payerEmail = payerEmail;
    }

    /**
     * Configuração sem o segredo do modo de teste.
     *
     * @param accessToken    {@code MP_ACCESS_TOKEN}
     * @param webhookSecret  {@code MP_WEBHOOK_SECRET}
     * @param webhookMaxSkew {@code MP_WEBHOOK_MAX_SKEW}
     * @param pixMinutes     {@code PIX_EXPIRES_MINUTES}
     * @param siteUrl        {@code SITE_URL}
     * @param payerEmail     {@code PAY_LINK_PAYER_EMAIL}
     */
    public PaymentSettings(String accessToken, String webhookSecret, Duration webhookMaxSkew, int pixMinutes,
            String siteUrl, String payerEmail) {
        this(accessToken, webhookSecret, "", webhookMaxSkew, pixMinutes, siteUrl, payerEmail);
    }

    /**
     * Diz se o Mercado Pago está configurado ({@code mpConfigured}).
     *
     * @return verdadeiro se há token
     */
    public boolean configured() {
        return !accessToken.isEmpty();
    }

    /**
     * Token de acesso.
     *
     * @return o token
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * Segredo das notificações.
     *
     * @return o segredo (vazio = webhook desligado)
     */
    public String webhookSecret() {
        return webhookSecret;
    }

    /**
     * Segredo das notificações do modo de teste.
     *
     * @return o segredo (vazio = notificações de teste não são aceitas)
     */
    public String webhookTestSecret() {
        return webhookTestSecret;
    }

    /**
     * Distância máxima do {@code ts}.
     *
     * @return a janela
     */
    public Duration webhookMaxSkew() {
        return webhookMaxSkew;
    }

    /**
     * Validade do Pix (31 min a 30 dias).
     *
     * @return a validade
     */
    public Duration pixValidity() {
        return pixValidity;
    }

    /**
     * Endereço completo de um caminho do site.
     *
     * @param path caminho começando com {@code /}
     * @return a URL
     */
    public String siteUrl(String path) {
        return siteUrl + path;
    }

    /**
     * E-mail do pagador no link: o do {@code .env} ou {@code pagamentos@<domínio do site>}.
     *
     * @return o e-mail
     */
    public String payerEmail() {
        if (payerEmail != null && !payerEmail.isBlank()) {
            return payerEmail;
        }
        String host = URI.create(siteUrl).getHost().toLowerCase(Locale.ROOT);
        return "pagamentos@" + host.replaceFirst("^www\\.", "");
    }
}
