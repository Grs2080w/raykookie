package br.com.kntap.raykookie.catalog.domain;

import java.util.List;

/**
 * Preço de um combo. Sugerido: a soma dos produtos avulsos menos o desconto de combo, nunca abaixo do custo mais a
 * margem mínima, arredondado para cima em R$ 0,50. O preço escolhido pela dona vale no lugar do sugerido.
 */
public final class ComboPricing {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private ComboPricing() {
    }

    /**
     * Calcula o combo.
     *
     * @param parts        produtos do combo, com preço e custo por unidade de cada um
     * @param discountPct  desconto de combo (%)
     * @param minMarginPct margem mínima (%) sobre o custo
     * @param fixedPrice   preço escolhido (nulo = o sugerido)
     * @return soma avulsa, custo, sugerido, preço, lucro e margem
     */
    public static Result calc(List<Part> parts, double discountPct, double minMarginPct, Double fixedPrice) {

        // ── 1. Soma avulsa e custo.
        double full = 0;
        double cost = 0;
        for (Part part : parts) {
            full += part.qty() * part.price();
            cost += part.qty() * part.unitCost();
        }

        // ── 2. Sugerido: desconto sobre a soma, com o piso do custo + margem mínima.
        double discounted = full * (1 - discountPct / 100);
        double floor = cost * (1 + minMarginPct / 100);
        double suggested = roundUp(Math.max(discounted, floor));

        // ── 3. Preço que vale, lucro e margem.
        double price = fixedPrice != null && fixedPrice > 0 ? fixedPrice : suggested;
        double profit = price - cost;
        double marginPct = cost > 0 ? profit / cost * 100 : 0;
        return new Result(cents(full), cents(cost), suggested, cents(price), cents(profit),
                Math.round(marginPct * 10) / 10.0);
    }

    /**
     * Arredonda para cima em R$ 0,50 (depois de tirar o ruído de ponto flutuante).
     *
     * @param value valor
     * @return o valor arredondado
     */
    static double roundUp(double value) {
        return Math.ceil(Math.round(value * 100) / 50.0) / 2.0;
    }

    /**
     * Valor em centavos exatos.
     *
     * @param value valor
     * @return o valor com 2 casas
     */
    private static double cents(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * Um produto no combo.
     *
     * @param qty      quantidade
     * @param price    preço avulso de cada unidade
     * @param unitCost custo de cada unidade (da receita padrão)
     */
    public record Part(int qty, double price, double unitCost) {
    }

    /**
     * O combo calculado.
     *
     * @param fullPrice soma dos produtos avulsos
     * @param cost      custo dos produtos
     * @param suggested preço sugerido
     * @param price     preço que vale (o escolhido ou o sugerido)
     * @param profit    lucro com o preço que vale
     * @param marginPct margem sobre o custo (%)
     */
    public record Result(double fullPrice, double cost, double suggested, double price, double profit,
            double marginPct) {

        /**
         * Diz se o preço não cobre o custo.
         *
         * @return verdadeiro se o lucro for negativo
         */
        public boolean belowCost() {
            return profit < 0;
        }
    }
}
