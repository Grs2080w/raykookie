package br.com.kntap.raykookie.ratelimit;

import java.time.Duration;

/**
 * Os limites de tentativa do sistema (Tarefa 2.4). Os de login repetem os números do Node
 * ({@code backend/lib/auth.ts}: 8 tentativas em 10 minutos); os demais são novos (G04, G11).
 */
public final class RateLimits {

    /** Login da dona, por {@code usuario|ip}. */
    public static final RateLimit OWNER_LOGIN = new RateLimit("owner-login", 8, Duration.ofMinutes(10));

    /** Login do cliente, por {@code email|ip}. */
    public static final RateLimit CUSTOMER_LOGIN = new RateLimit("customer-login", 8, Duration.ofMinutes(10));

    /** Cadastro de cliente, por IP. */
    public static final RateLimit CUSTOMER_SIGNUP = new RateLimit("customer-signup", 5, Duration.ofHours(1));

    /** Pedidos criados, por cliente (G11). */
    public static final RateLimit ORDER_CREATE = new RateLimit("order-create", 10, Duration.ofHours(1));

    /** Cartões recusados, por cliente (G04). */
    public static final RateLimit CARD_FAIL_CUSTOMER = new RateLimit("card-fail-customer", 5, Duration.ofHours(24));

    /** Cartões recusados, por IP (G04). */
    public static final RateLimit CARD_FAIL_IP = new RateLimit("card-fail-ip", 10, Duration.ofHours(24));

    /** Pix gerados, por cliente (G11). */
    public static final RateLimit PIX_CREATE = new RateLimit("pix-create", 20, Duration.ofHours(1));

    /** A maior janela entre os limites: a limpeza diária apaga só o que é mais velho que isso. */
    public static final Duration LONGEST_WINDOW = Duration.ofHours(24);

    /**
     * Sem instâncias: só constantes.
     */
    private RateLimits() {
    }
}
