package br.com.kntap.raykookie.catalog.api;

import br.com.kntap.raykookie.kernel.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Cálculo de custo e preço das receitas: porte exato de {@code backend/shared/pricing.ts} e
 * {@code backend/shared/margin.ts}.
 *
 * <p>As contas intermediárias ficam em {@code double}, na mesma ordem do TypeScript: as operações do IEEE 754
 * dão o mesmo resultado bit a bit nas duas linguagens, então o preço sugerido, o arredondado e o lucro saem
 * idênticos aos do Node (conferido com {@code rules-vectors/pricing.json}). O preço final, sempre múltiplo de
 * R$ 0,50 ou o preço fixado pela dona, vira {@link Money} em {@link Result#price()}.
 */
public final class PricingEngine {

    /** Queda de margem (pontos percentuais) que já merece aviso, mesmo acima do mínimo. */
    public static final double MARGIN_DROP_POINTS = 5;

    /** Aumento do preço sugerido que merece aviso quando não há preço fixado. */
    public static final double PRICE_RISE_PCT = 5;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private PricingEngine() {
    }

    /**
     * Custo por unidade-base (R$ por g, ml ou un).
     *
     * @param packageCost custo da embalagem
     * @param packageSize tamanho da embalagem
     * @return o custo por unidade-base; 0 se a embalagem for 0
     */
    public static double unitCost(double packageCost, double packageSize) {
        return packageSize > 0 ? packageCost / packageSize : 0;
    }

    /**
     * Situação do estoque.
     *
     * @param stockQty quantidade em estoque
     * @param minStock estoque mínimo
     * @return {@code zerado}, {@code baixo} ou {@code ok}
     */
    public static String stockStatus(double stockQty, double minStock) {
        if (stockQty <= 0) {
            return "zerado";
        }
        return stockQty <= minStock ? "baixo" : "ok";
    }

    /**
     * Arredonda para cima em R$ 0,50 (com a mesma folga de {@code 1e-9} do Node).
     *
     * @param value valor
     * @return o valor arredondado
     */
    public static double roundUpHalf(double value) {
        return Math.ceil(value * 2 - 1e-9) / 2;
    }

    /**
     * Calcula o custo e o preço de uma receita ({@code calcRecipe}).
     *
     * @param input a receita e os parâmetros da dona
     * @return o resultado
     */
    public static Result calculate(Input input) {

        // ── 1. Custos: ingredientes, mão de obra e rateio dos gastos fixos.
        double yieldUnits = input.yieldUnits() > 0 ? input.yieldUnits() : 1;
        double ingredients = 0;
        for (Item item : input.items()) {
            ingredients = ingredients + item.qty() * item.unitCost();
        }
        double labor = input.prepHours() * input.hourlyRate();
        double overhead = input.monthlyUnits() > 0 ? (input.fixedMonthly() / input.monthlyUnits()) * yieldUnits : 0;

        // ── 2. Preço: custo por unidade, margem e arredondamento.
        double total = ingredients + labor + overhead;
        double perUnit = total / yieldUnits;
        double suggested = perUnit * (1 + input.marginPct() / 100);
        double rounded = perUnit > 0 ? roundUpHalf(suggested) : 0;

        // ── 3. Lucro e margem real sobre o preço.
        double profitPerUnit = rounded - perUnit;
        double realMarginPct = rounded > 0 ? (profitPerUnit / rounded) * 100 : 0;
        return new Result(ingredients, labor, overhead, total, perUnit, suggested, rounded, profitPerUnit,
                profitPerUnit * yieldUnits, realMarginPct);
    }

    /**
     * Preço do cardápio fixado pela dona: vale no lugar do sugerido, e lucro e margem são refeitos com ele
     * ({@code withSalePrice}).
     *
     * @param pricing o cálculo
     * @param sale    preço fixado (nulo ou 0 = nenhum)
     * @return o cálculo com o preço fixado, ou o mesmo cálculo
     */
    public static Result withSalePrice(Result pricing, Double sale) {
        if (sale == null || !(sale > 0) || pricing.perUnit() <= 0) {
            return pricing;
        }
        double yieldUnits = pricing.total() / pricing.perUnit();
        double profitPerUnit = sale - pricing.perUnit();
        return new Result(pricing.ingredients(), pricing.labor(), pricing.overhead(), pricing.total(),
                pricing.perUnit(), pricing.suggested(), sale, profitPerUnit, profitPerUnit * yieldUnits,
                (profitPerUnit / sale) * 100);
    }

    /**
     * Alerta de margem ({@code marginAlert}): com preço fixado, avisa quando a margem real cai abaixo do mínimo
     * ou perde 5 pontos ou mais; sem preço fixado, avisa quando o sugerido sobe 5% ou mais.
     *
     * @param before         cálculo antes da mudança
     * @param after          cálculo depois
     * @param withSale       se a receita tem preço fixado
     * @param minMarginPct   margem mínima da dona
     * @param suggestedAfter preço sugerido depois da mudança
     * @return o alerta, se houver
     */
    public static Optional<MarginAlert> marginAlert(Result before, Result after, boolean withSale,
            double minMarginPct, double suggestedAfter) {

        // ── 1. Preço fixado: olha a margem.
        if (withSale) {
            double b = before.realMarginPct();
            double a = after.realMarginPct();
            if (a < b - 0.05 && (a < minMarginPct || b - a >= MARGIN_DROP_POINTS)) {
                return Optional.of(new MarginAlert("margem", round2(b), round2(a), suggestedAfter));
            }
            return Optional.empty();
        }

        // ── 2. Sem preço fixado: olha quanto o sugerido subiu.
        if (before.rounded() > 0 && after.rounded() > before.rounded()
                && (after.rounded() / before.rounded() - 1) * 100 >= PRICE_RISE_PCT) {
            return Optional.of(new MarginAlert("preco", before.rounded(), after.rounded(), after.rounded()));
        }
        return Optional.empty();
    }

    /**
     * {@code Math.round(n * 100) / 100} do JavaScript.
     *
     * @param number número
     * @return o número com 2 casas
     */
    public static double round2(double number) {
        return (double) Math.round(number * 100) / 100;
    }

    /**
     * {@code n.toFixed(2).replace('.', ',')} do JavaScript (arredonda o valor binário exato, empate para cima).
     *
     * @param number número não negativo
     * @return o texto com vírgula e 2 casas
     */
    public static String fixed2(double number) {
        return new BigDecimal(number).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Um ingrediente na receita.
     *
     * @param qty      quantidade usada (g, ml ou un)
     * @param unitCost custo por unidade-base
     */
    public record Item(double qty, double unitCost) {
    }

    /**
     * Entrada do cálculo.
     *
     * @param items        ingredientes
     * @param yieldUnits   unidades que a receita rende
     * @param marginPct    acréscimo sobre o custo por unidade (%)
     * @param prepHours    horas de preparo
     * @param hourlyRate   valor da hora de trabalho
     * @param fixedMonthly soma dos gastos fixos e variáveis do mês
     * @param monthlyUnits unidades produzidas por mês (base do rateio)
     */
    public record Input(List<Item> items, double yieldUnits, double marginPct, double prepHours, double hourlyRate,
            double fixedMonthly, double monthlyUnits) {
    }

    /**
     * Resultado do cálculo, com os nomes de campo do Node.
     *
     * @param ingredients   custo dos ingredientes
     * @param labor         mão de obra
     * @param overhead      rateio dos gastos fixos
     * @param total         custo total
     * @param perUnit       custo por unidade
     * @param suggested     preço sugerido (custo + margem)
     * @param rounded       preço arredondado em R$ 0,50 (ou o fixado)
     * @param profitPerUnit lucro por unidade
     * @param profitTotal   lucro da receita inteira
     * @param realMarginPct lucro sobre o preço (%)
     */
    public record Result(double ingredients, double labor, double overhead, double total, double perUnit,
            double suggested, double rounded, double profitPerUnit, double profitTotal, double realMarginPct) {

        /**
         * Preço de venda em centavos.
         *
         * @return o preço arredondado (ou fixado)
         */
        public Money price() {
            return Money.ofCents(Math.round(rounded * 100));
        }
    }

    /**
     * Alerta de margem ou de preço.
     *
     * @param kind      {@code margem} ou {@code preco}
     * @param before    margem (%) ou preço antes
     * @param after     margem (%) ou preço depois
     * @param suggested preço sugerido novo
     */
    public record MarginAlert(String kind, double before, double after, Double suggested) {
    }
}
