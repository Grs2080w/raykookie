package br.com.kntap.raykookie.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A configuração errada impede a subida com uma mensagem que cita a variável do .env (G14, G15, G31).
 */
class ConfigurationValidationTest {

    /** Segredo válido (32 caracteres). */
    private static final String SECRET = "rayka.download-secret=0123456789abcdef0123456789abcdef";

    /** Contexto mínimo: só a configuração validada. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RaykookieConfig.class);

    /**
     * Configuração mínima válida: sobe, com os padrões do Node.
     */
    @Test
    @DisplayName("configuração mínima válida sobe com os padrões")
    void validMinimum() {
        runner.withPropertyValues(SECRET).run(context -> {
            RaykookieProperties properties = context.getBean(RaykookieProperties.class);
            assertThat(properties.secureCookies()).isFalse();
            assertThat(properties.registrationOpen()).isFalse();
            assertThat(properties.jobsOn()).isTrue();
            assertThat(properties.cardRequested()).isFalse();
            assertThat(properties.shopOwner()).isEqualTo("rayka");
            assertThat(properties.pixExpiresMinutes()).isEqualTo(60);
            assertThat(properties.mp().apiBase()).isEqualTo("https://api.mercadopago.com");
            assertThat(properties.mp().webhookMaxSkew()).hasMinutes(10);
            assertThat(properties.payerEmail()).isEqualTo("pagamentos@raykookie.com.br");
        });
    }

    /**
     * Comentário na mesma linha do .env: no Node virava "falso" sem avisar; aqui não sobe.
     */
    @Test
    @DisplayName("COOKIE_SECURE=\"true   # comentário\": não sobe e cita COOKIE_SECURE")
    void inlineCommentIsRejected() {
        runner.withPropertyValues(SECRET, "rayka.cookie-secure=true   # comentário").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("COOKIE_SECURE");
        });
    }

    /**
     * Valores inválidos: cada um cita a sua variável.
     *
     * @param property propriedade com valor inválido
     * @param variable variável que a mensagem precisa citar
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "rayka.download-secret=     | DOWNLOAD_SECRET",
        "rayka.download-secret=0123456789 | DOWNLOAD_SECRET",
        "rayka.registration-enabled=sim | REGISTRATION_ENABLED",
        "rayka.jobs-enabled=1 | JOBS_ENABLED",
        "rayka.pix-expires-minutes=30 | PIX_EXPIRES_MINUTES",
        "rayka.pix-expires-minutes=43201 | PIX_EXPIRES_MINUTES",
        "rayka.site-url=http://raykookie.com.br | SITE_URL",
        "rayka.shop-owner= | SHOP_OWNER",
        "rayka.mp.api-base=ftp://x | MP_API_BASE",
    })
    @DisplayName("valor inválido: não sobe e cita a variável")
    void invalidValues(String property, String variable) {
        String secret = property.startsWith("rayka.download-secret") ? property : SECRET;
        runner.withPropertyValues(secret, property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(variable);
        });
    }

    /**
     * Sem DOWNLOAD_SECRET nenhum: não sobe (G14; no Node, o segredo caía num valor derivado do banco).
     */
    @Test
    @DisplayName("sem DOWNLOAD_SECRET: não sobe")
    void missingDownloadSecret() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("DOWNLOAD_SECRET");
        });
    }

    /**
     * Cartão ligado sem chave pública ou sem token: não sobe, citando o que falta (G31).
     */
    @Test
    @DisplayName("PAYMENTS_CARD_ENABLED=true sem MP_PUBLIC_KEY ou sem MP_ACCESS_TOKEN: não sobe")
    void cardNeedsKeys() {

        // ── 1. Sem a chave pública (o caso real de produção em 2026-10-07).
        runner.withPropertyValues(SECRET, "rayka.payments-card-enabled=true", "rayka.mp.access-token=APP_USR-x")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("MP_PUBLIC_KEY"));

        // ── 2. Sem o token.
        runner.withPropertyValues(SECRET, "rayka.payments-card-enabled=true", "rayka.mp.public-key=APP_USR-y")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("MP_ACCESS_TOKEN"));

        // ── 3. Com os dois, sobe.
        runner.withPropertyValues(SECRET, "rayka.payments-card-enabled=true", "rayka.mp.public-key=APP_USR-y",
                "rayka.mp.access-token=APP_USR-x").run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Site em http só em localhost; e-mail do pagador configurado vence o padrão.
     */
    @Test
    @DisplayName("http://localhost vale; PAY_LINK_PAYER_EMAIL configurado vence o padrão")
    void localSiteAndPayerEmail() {
        runner.withPropertyValues(SECRET, "rayka.site-url=http://localhost:10005",
                "rayka.pay-link-payer-email= pix@loja.com ").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RaykookieProperties.class).payerEmail()).isEqualTo("pix@loja.com");
                });
        runner.withPropertyValues(SECRET, "rayka.site-url=https://www.exemplo.com.br/").run(context ->
                assertThat(context.getBean(RaykookieProperties.class).payerEmail())
                        .isEqualTo("pagamentos@exemplo.com.br"));
    }
}
