package br.com.kntap.raykookie.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Toda a configuração do servidor ({@code rayka.*}), conferida na subida (G14, G15, G31): valor errado
 * impede o servidor de subir, com uma mensagem que cita a variável do {@code .env}. O mapeamento das
 * variáveis para {@code rayka.*} fica no {@code application.yml}.
 *
 * <p>Os interruptores ({@code COOKIE_SECURE}, {@code REGISTRATION_ENABLED}, {@code JOBS_ENABLED},
 * {@code PAYMENTS_CARD_ENABLED}) aceitam só {@code true} ou {@code false}: um comentário na mesma linha do
 * {@code .env} ({@code COOKIE_SECURE=true # produção}) virava "falso" em silêncio no Node.
 *
 * @param siteUrl                    endereço público do site ({@code SITE_URL}); https fora de localhost
 * @param cookieSecure               cookies só por https ({@code COOKIE_SECURE})
 * @param shopOwner                  usuário da dona da loja ({@code SHOP_OWNER})
 * @param registrationEnabled        cadastro de dona aberto ({@code REGISTRATION_ENABLED}; G01)
 * @param downloadSecret             segredo dos links de download ({@code DOWNLOAD_SECRET}; G14)
 * @param downloadsDir               pasta dos APKs ({@code DOWNLOADS_DIR})
 * @param jobsEnabled                tarefas agendadas ligadas ({@code JOBS_ENABLED})
 * @param paymentsCardEnabled        pagamento com cartão ligado ({@code PAYMENTS_CARD_ENABLED}; G31)
 * @param pixExpiresMinutes          validade do Pix em minutos ({@code PIX_EXPIRES_MINUTES})
 * @param payLinkPayerEmail          e-mail do pagador nos Pix do link ({@code PAY_LINK_PAYER_EMAIL})
 * @param firebaseServiceAccountPath JSON da conta de serviço do Firebase ({@code FIREBASE_SERVICE_ACCOUNT_PATH})
 * @param alertWebhookUrl            webhook de alertas ({@code ALERT_WEBHOOK_URL})
 * @param mp                         Mercado Pago
 * @param google                     login com Google
 */
@Validated
@ConfigurationProperties("rayka")
public record RaykookieProperties(
        @DefaultValue("https://raykookie.com.br") @NotBlank(message = "SITE_URL é obrigatório.")
        String siteUrl,
        @DefaultValue("false") @Pattern(regexp = SWITCH, message = "COOKIE_SECURE" + SWITCH_MESSAGE)
        String cookieSecure,
        @DefaultValue("rayka") @NotBlank(message = "SHOP_OWNER é obrigatório.") String shopOwner,
        @DefaultValue("false") @Pattern(regexp = SWITCH, message = "REGISTRATION_ENABLED" + SWITCH_MESSAGE)
        String registrationEnabled,
        @NotBlank(message = "DOWNLOAD_SECRET é obrigatório (gere com: openssl rand -hex 32).")
        @Size(min = 32, message = "DOWNLOAD_SECRET precisa ter pelo menos 32 caracteres.") String downloadSecret,
        @DefaultValue("downloads") @NotBlank(message = "DOWNLOADS_DIR não pode ser vazio.") String downloadsDir,
        @DefaultValue("true") @Pattern(regexp = SWITCH, message = "JOBS_ENABLED" + SWITCH_MESSAGE) String jobsEnabled,
        @DefaultValue("false") @Pattern(regexp = SWITCH, message = "PAYMENTS_CARD_ENABLED" + SWITCH_MESSAGE)
        String paymentsCardEnabled,
        @DefaultValue("60") @Min(value = 31, message = "PIX_EXPIRES_MINUTES precisa ser de 31 a 43200.")
        @Max(value = 43200, message = "PIX_EXPIRES_MINUTES precisa ser de 31 a 43200.") int pixExpiresMinutes,
        String payLinkPayerEmail,
        String firebaseServiceAccountPath,
        String alertWebhookUrl,
        @DefaultValue @Valid @NotNull Mp mp,
        @DefaultValue @Valid @NotNull Google google) {

    /** Valor aceito pelos interruptores. */
    static final String SWITCH = "true|false";

    /** Final da mensagem dos interruptores. */
    static final String SWITCH_MESSAGE = " precisa ser true ou false (sem comentário na mesma linha do .env).";

    /** Hosts em que o site pode ser http (desenvolvimento). */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /**
     * Cookies só por https.
     *
     * @return o valor de {@code COOKIE_SECURE}
     */
    public boolean secureCookies() {
        return Boolean.parseBoolean(cookieSecure);
    }

    /**
     * Cadastro de dona aberto (só vale com a tabela {@code users} vazia; G01).
     *
     * @return o valor de {@code REGISTRATION_ENABLED}
     */
    public boolean registrationOpen() {
        return Boolean.parseBoolean(registrationEnabled);
    }

    /**
     * Tarefas agendadas ligadas.
     *
     * @return o valor de {@code JOBS_ENABLED}
     */
    public boolean jobsOn() {
        return Boolean.parseBoolean(jobsEnabled);
    }

    /**
     * Cartão pedido na configuração (a conferência do Mercado Pago na subida ainda pode desligá-lo).
     *
     * @return o valor de {@code PAYMENTS_CARD_ENABLED}
     */
    public boolean cardRequested() {
        return Boolean.parseBoolean(paymentsCardEnabled);
    }

    /**
     * E-mail do pagador nos Pix do link de pagamento: o configurado, ou {@code pagamentos@<domínio do site>}
     * (a mesma regra de {@code backend/lib/pay-link.ts}).
     *
     * @return o e-mail
     */
    public String payerEmail() {
        if (payLinkPayerEmail != null && !payLinkPayerEmail.isBlank()) {
            return payLinkPayerEmail.strip();
        }
        String host = URI.create(siteUrl).getHost().toLowerCase(Locale.ROOT);
        return "pagamentos@" + host.replaceFirst("^www\\.", "");
    }

    /**
     * {@code SITE_URL} é uma URL http(s) e, fora de localhost, https.
     *
     * @return verdadeiro se é válida
     */
    @AssertTrue(message = "SITE_URL precisa ser uma URL https (http só em localhost).")
    public boolean isSiteUrlValid() {
        if (siteUrl == null) {
            return false;
        }
        try {
            URI uri = URI.create(siteUrl.strip());
            boolean local = uri.getHost() != null && LOCAL_HOSTS.contains(uri.getHost());
            return "https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * Com o cartão ligado, a chave pública é obrigatória (G31: sem ela o site e o app não tokenizam).
     *
     * @return verdadeiro se a regra é cumprida
     */
    @AssertTrue(message = "PAYMENTS_CARD_ENABLED=true exige MP_PUBLIC_KEY.")
    public boolean isCardPublicKeyPresent() {
        return !cardRequested() || mp != null && mp.hasPublicKey();
    }

    /**
     * Com o cartão ligado, o access token é obrigatório.
     *
     * @return verdadeiro se a regra é cumprida
     */
    @AssertTrue(message = "PAYMENTS_CARD_ENABLED=true exige MP_ACCESS_TOKEN.")
    public boolean isCardAccessTokenPresent() {
        return !cardRequested() || mp != null && mp.hasAccessToken();
    }

    /**
     * Configuração do Mercado Pago.
     *
     * @param accessToken    access token ({@code MP_ACCESS_TOKEN})
     * @param publicKey      chave pública ({@code MP_PUBLIC_KEY})
     * @param webhookSecret  segredo da assinatura do webhook ({@code MP_WEBHOOK_SECRET})
     * @param apiBase        endereço da API ({@code MP_API_BASE}; os testes apontam para o fake)
     * @param webhookMaxSkew diferença máxima aceita no {@code ts} da assinatura (G18)
     */
    public record Mp(
            String accessToken,
            String publicKey,
            String webhookSecret,
            @DefaultValue("https://api.mercadopago.com")
            @Pattern(regexp = "https?://\\S+", message = "MP_API_BASE precisa ser uma URL http(s).") String apiBase,
            @DefaultValue("10m") @NotNull Duration webhookMaxSkew) {

        /**
         * Há chave pública.
         *
         * @return verdadeiro se {@code MP_PUBLIC_KEY} não é vazia
         */
        public boolean hasPublicKey() {
            return publicKey != null && !publicKey.isBlank();
        }

        /**
         * Há access token.
         *
         * @return verdadeiro se {@code MP_ACCESS_TOKEN} não é vazio
         */
        public boolean hasAccessToken() {
            return accessToken != null && !accessToken.isBlank();
        }
    }

    /**
     * Login com Google (opcional: sem os dois valores, o botão some).
     *
     * @param clientId     {@code GOOGLE_CLIENT_ID}
     * @param clientSecret {@code GOOGLE_CLIENT_SECRET}
     */
    public record Google(String clientId, String clientSecret) {
    }
}
