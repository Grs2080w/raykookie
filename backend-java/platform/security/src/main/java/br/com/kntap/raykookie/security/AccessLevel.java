package br.com.kntap.raykookie.security;

/**
 * Quem pode chamar uma rota. Toda rota declara o seu com {@link Access}; sem declaração, a rota
 * responde 500 (fail-closed).
 */
public enum AccessLevel {

    /** Qualquer um, sem sessão. */
    PUBLIC,

    /** Só a dona da loja ({@code SHOP_OWNER}), com sessão válida (G01, G24). */
    OWNER,

    /** Só um cliente com sessão válida. */
    CUSTOMER,

    /** Qualquer um; se houver sessão da dona, a rota pode mostrar mais (ex.: downloads, fotos). */
    OWNER_OR_PUBLIC,

    /** Quem tem o token do link de pagamento; a própria rota confere o token. */
    PAY_LINK_TOKEN,

    /** Notificação assinada do Mercado Pago; a própria rota confere a assinatura. */
    SIGNED_WEBHOOK
}
