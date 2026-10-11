package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras dos cupons: código, porcentagem, valor mínimo e o desconto aplicado ao subtotal do pedido.
 */
public final class CouponRules {

    /** Cupom que não existe ou foi desativado. */
    public static final String INVALID = "Cupom inválido.";

    /** Maior desconto (%). */
    public static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(90);

    /** Menor total depois do desconto (o Mercado Pago não cobra zero). */
    public static final Money MIN_TOTAL = Money.ofCents(100);

    /** Código escolhido pela dona: letras, números e hífen, de 3 a 20. */
    private static final Pattern CODE = Pattern.compile("[A-Z0-9-]{3,20}");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private CouponRules() {
    }

    /**
     * Código como o cliente digita, normalizado (maiúsculas, sem espaços).
     *
     * @param raw texto
     * @return o código
     */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Confere o código escolhido pela dona.
     *
     * @param code código normalizado
     * @return o código
     * @throws InvalidInputException fora do formato
     */
    public static String requireCode(String code) {
        if (!CODE.matcher(code).matches()) {
            throw new InvalidInputException("O código do cupom tem de 3 a 20 letras, números ou hífen.");
        }
        return code;
    }

    /**
     * Aplica o cupom ao subtotal.
     *
     * @param coupon   cupom (nulo = não existe)
     * @param subtotal subtotal do pedido, pelo cardápio do servidor
     * @return o desconto aplicado
     * @throws InvalidInputException com a mensagem para o cliente
     */
    public static Applied apply(Coupon coupon, Money subtotal) {

        // ── 1. Cupom ativo.
        if (coupon == null || !coupon.active()) {
            throw new InvalidInputException(INVALID);
        }

        // ── 2. Valor mínimo ("a partir de" ou "acima de").
        Money min = coupon.minTotal();
        if (min != null && (coupon.minInclusive() ? subtotal.isLessThan(min) : !min.isLessThan(subtotal))) {
            String rule = coupon.minInclusive() ? "a partir de " : "acima de ";
            String missing = coupon.minInclusive() ? " — faltam " + min.minus(subtotal).toBrl() + "." : ".";
            throw new InvalidInputException("Este cupom vale para pedidos " + rule + min.toBrl() + missing);
        }

        // ── 3. Desconto em centavos; o total não pode ficar abaixo de R$ 1,00.
        long cents = coupon.percent().multiply(BigDecimal.valueOf(subtotal.cents()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
        Money discount = Money.ofCents(Math.min(cents, subtotal.cents()));
        if (subtotal.minus(discount).isLessThan(MIN_TOTAL)) {
            throw new InvalidInputException("Com este cupom o total fica abaixo de R$ 1,00. Adicione mais itens.");
        }
        return new Applied(coupon.id(), coupon.code(), coupon.percent(), discount);
    }

    /**
     * Um cupom gravado.
     *
     * @param id           id
     * @param code         código
     * @param percent      desconto (%)
     * @param minTotal     valor mínimo (nulo = nenhum)
     * @param minInclusive "a partir de" (verdadeiro) ou "acima de" (falso)
     * @param active       se está ativo
     */
    public record Coupon(long id, String code, BigDecimal percent, Money minTotal, boolean minInclusive,
            boolean active) {
    }

    /**
     * Cupom aplicado a um pedido.
     *
     * @param couponId id do cupom
     * @param code     código
     * @param percent  desconto (%)
     * @param discount valor descontado
     */
    public record Applied(long couponId, String code, BigDecimal percent, Money discount) {
    }
}
