package br.com.kntap.raykookie.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras dos cupons.
 */
class CouponRulesTest {

    /**
     * Cupom de teste.
     *
     * @param pct       desconto
     * @param minCents  mínimo em centavos (0 = nenhum)
     * @param inclusive "a partir de"
     * @param active    ativo
     * @return o cupom
     */
    private static CouponRules.Coupon coupon(int pct, long minCents, boolean inclusive, boolean active) {
        return new CouponRules.Coupon(1, "NATAL20", BigDecimal.valueOf(pct), minCents == 0 ? null
                : Money.ofCents(minCents), inclusive, active);
    }

    /**
     * Desconto.
     */
    @Test
    @DisplayName("desconto: porcentagem do subtotal arredondada ao centavo")
    void discount() {
        assertThat(CouponRules.apply(coupon(20, 0, true, true), Money.ofCents(1_999)).discount())
                .isEqualTo(Money.ofCents(400));
        assertThat(CouponRules.normalize(" natal 20 ")).isEqualTo("NATAL20");
        assertThat(CouponRules.requireCode("NATAL-20")).isEqualTo("NATAL-20");
        assertThatThrownBy(() -> CouponRules.requireCode("NA")).isInstanceOf(InvalidInputException.class);
    }

    /**
     * Valor mínimo.
     */
    @Test
    @DisplayName("mínimo: 'a partir de' aceita igual e diz quanto falta; 'acima de' exige mais que o valor")
    void minimum() {
        assertThat(CouponRules.apply(coupon(10, 1_500, true, true), Money.ofCents(1_500)).discount())
                .isEqualTo(Money.ofCents(150));
        assertThatThrownBy(() -> CouponRules.apply(coupon(10, 1_500, true, true), Money.ofCents(1_200)))
                .hasMessage("Este cupom vale para pedidos a partir de R$ 15,00 — faltam R$ 3,00.");
        assertThatThrownBy(() -> CouponRules.apply(coupon(10, 1_500, false, true), Money.ofCents(1_500)))
                .hasMessage("Este cupom vale para pedidos acima de R$ 15,00.");
        assertThat(CouponRules.apply(coupon(10, 1_500, false, true), Money.ofCents(1_501)).discount())
                .isEqualTo(Money.ofCents(150));
    }

    /**
     * Inválido e total mínimo.
     */
    @Test
    @DisplayName("inválido: inexistente ou desativado; total não fica abaixo de R$ 1,00")
    void invalid() {
        assertThatThrownBy(() -> CouponRules.apply(null, Money.ofCents(1_000))).hasMessage(CouponRules.INVALID);
        assertThatThrownBy(() -> CouponRules.apply(coupon(10, 0, true, false), Money.ofCents(1_000)))
                .hasMessage(CouponRules.INVALID);
        assertThatThrownBy(() -> CouponRules.apply(coupon(90, 0, true, true), Money.ofCents(500)))
                .hasMessageContaining("abaixo de R$ 1,00");
    }
}
