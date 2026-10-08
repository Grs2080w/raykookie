package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.catalog.api.PricingEngine;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;

/**
 * Propriedades do cálculo de preço com milhares de receitas aleatórias.
 */
class PricingPropertiesTest {

    /**
     * Preço sugerido nunca negativo, arredondado nunca abaixo do sugerido, sempre múltiplo de R$ 0,50.
     *
     * @param quantities quantidades dos ingredientes
     * @param unitCost   custo por unidade-base
     * @param yieldUnits rendimento
     * @param margin     margem
     * @param fixed      gastos fixos do mês
     */
    @Property(tries = 2000)
    void roundedNeverBelowSuggested(@ForAll @Size(max = 8) List<@DoubleRange(min = 0, max = 5000) Double> quantities,
            @ForAll @DoubleRange(min = 0, max = 2) double unitCost, @ForAll @DoubleRange(min = 0, max = 500) double yieldUnits,
            @ForAll @DoubleRange(min = 0, max = 400) double margin, @ForAll @DoubleRange(min = 0, max = 9000) double fixed) {

        // ── 1. Uma receita qualquer.
        List<PricingEngine.Item> items = quantities.stream().map(qty -> new PricingEngine.Item(qty, unitCost)).toList();
        PricingEngine.Result result = PricingEngine.calculate(
                new PricingEngine.Input(items, yieldUnits, margin, 1, 20, fixed, 100));

        // ── 2. As propriedades.
        assertThat(result.suggested()).isGreaterThanOrEqualTo(0);
        assertThat(result.rounded()).isGreaterThanOrEqualTo(result.suggested() - 1e-9);
        assertThat(result.rounded() * 2).isEqualTo(Math.rint(result.rounded() * 2));
        assertThat(result.price().cents() % 50).isZero();
    }

    /**
     * Receita vazia não gera NaN nem infinito.
     *
     * @param yieldUnits rendimento (inclusive zero)
     * @param units      produção mensal (inclusive zero)
     */
    @Property(tries = 300)
    void emptyRecipeIsFinite(@ForAll @DoubleRange(min = 0, max = 50) double yieldUnits,
            @ForAll @DoubleRange(min = 0, max = 50) double units) {
        PricingEngine.Result result = PricingEngine.calculate(
                new PricingEngine.Input(List.of(), yieldUnits, 50, 0, 0, 0, units));
        double[] all = {result.ingredients(), result.labor(), result.overhead(), result.total(), result.perUnit(),
            result.suggested(), result.rounded(), result.profitPerUnit(), result.profitTotal(), result.realMarginPct()};
        for (double value : all) {
            assertThat(Double.isFinite(value)).isTrue();
        }
    }
}
