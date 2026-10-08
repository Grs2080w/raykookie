package br.com.kntap.raykookie.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limites de tamanho do corpo das requisições ({@code rayka.web.*}), aplicados pelo
 * {@link RequestSizeLimitFilter} antes de qualquer leitura (G08).
 *
 * @param defaultMaxBodyBytes limite das rotas sem regra própria (JSON comum), em bytes
 * @param defaultMessage      mensagem do 413 nas rotas sem mensagem própria
 * @param bodyLimits          limites por rota; vazio = os limites padrão de {@link #defaultBodyLimits()}
 */
@Validated
@ConfigurationProperties("rayka.web")
public record WebProperties(
        @DefaultValue("1048576") @Positive long defaultMaxBodyBytes,
        @DefaultValue(DEFAULT_MESSAGE) @NotBlank String defaultMessage,
        @Valid List<BodyLimit> bodyLimits) {

    /** Mensagem padrão do 413. */
    public static final String DEFAULT_MESSAGE = "O envio é grande demais.";

    /** Limite do pedido de devolução e do feedback com fotos: o mesmo {@code 12 * 1024 * 1024} do Node. */
    private static final long FORM_WITH_PHOTOS_BYTES = 12L * 1024 * 1024;

    /** Mensagem do Node para formulário com fotos grande demais ({@code backend/lib/refund-form.ts}). */
    private static final String FORM_WITH_PHOTOS_MESSAGE = "O envio é grande demais (máximo de 12 MB no total).";

    /**
     * Completa a configuração: sem regras por rota, valem as do plano.
     *
     * @param defaultMaxBodyBytes limite das rotas sem regra própria
     * @param defaultMessage      mensagem padrão do 413
     * @param bodyLimits          limites por rota (pode vir nulo)
     */
    public WebProperties {
        if (bodyLimits == null || bodyLimits.isEmpty()) {
            bodyLimits = defaultBodyLimits();
        }
        bodyLimits = List.copyOf(bodyLimits);
    }

    /**
     * Limites por rota do plano (Tarefa 2.3), com os números e as mensagens que o Node já usa.
     *
     * @return os limites padrão
     */
    public static List<BodyLimit> defaultBodyLimits() {
        return List.of(
                new BodyLimit("PUT", "/api/recipes/*/photo", 2_000_000L, "A foto é grande demais (máximo de 2 MB)."),
                new BodyLimit("POST", "/api/cliente/pedidos/*/devolucao", FORM_WITH_PHOTOS_BYTES,
                        FORM_WITH_PHOTOS_MESSAGE),
                new BodyLimit("POST", "/api/pagar/*/devolucao", FORM_WITH_PHOTOS_BYTES, FORM_WITH_PHOTOS_MESSAGE),
                new BodyLimit("POST", "/api/cliente/feedback", FORM_WITH_PHOTOS_BYTES, FORM_WITH_PHOTOS_MESSAGE),
                new BodyLimit("POST", "/api/nota/ler", 3L * 1024 * 1024, null));
    }

    /**
     * Limite de uma rota.
     *
     * @param method   método HTTP (maiúsculo)
     * @param path     padrão do caminho, com {@code *} no lugar de um segmento (ex.: o id da receita)
     * @param maxBytes tamanho máximo do corpo, em bytes
     * @param message  mensagem do 413; nula = {@link WebProperties#defaultMessage()}
     */
    public record BodyLimit(
            @NotBlank String method,
            @NotBlank String path,
            @NotNull @Positive Long maxBytes,
            String message) {
    }
}
