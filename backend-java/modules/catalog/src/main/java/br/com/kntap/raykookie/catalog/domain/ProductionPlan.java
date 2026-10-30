package br.com.kntap.raykookie.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * O que produzir uma quantidade de uma receita consome: cada ingrediente (com o estoque de antes e de depois), o que
 * falta e o custo. Cálculo puro, sem banco: o caso de uso trava os estoques e passa os valores.
 *
 * <p>Consumo = (unidades produzidas ÷ rendimento da receita) × quantidade do ingrediente na receita, na unidade do
 * ingrediente (g, ml ou un). O estoque do ingrediente é guardado em <b>embalagens</b> (é o que a tela do estoque pede),
 * então o plano converte: embalagens = quantidade ÷ tamanho da embalagem. Estoque, falta e custo saem em g/ml/un.
 *
 * @param lines ingredientes da receita, na ordem em que ela os lista
 * @param cost  custo total dos ingredientes consumidos
 */
public record ProductionPlan(List<Line> lines, BigDecimal cost) {

    /**
     * Um ingrediente consumido.
     *
     * @param ingredientId ingrediente
     * @param name         nome
     * @param unit         unidade de medida
     * @param needed       quantidade que a produção consome (g, ml ou un)
     * @param stock        estoque de antes (g, ml ou un)
     * @param after        estoque de depois (nunca abaixo de zero: o que falta não vira estoque negativo)
     * @param missing      quanto falta (0 se o estoque cobre)
     * @param cost         custo da quantidade consumida
     * @param packsBefore  estoque de antes, em embalagens (como o banco guarda)
     * @param packsAfter   estoque de depois, em embalagens
     */
    public record Line(long ingredientId, String name, String unit, BigDecimal needed, BigDecimal stock,
            BigDecimal after, BigDecimal missing, BigDecimal cost, BigDecimal packsBefore, BigDecimal packsAfter) {

        /**
         * Quanto do estoque esta produção desconta de fato, em embalagens (o que existe, até o necessário).
         *
         * @return as embalagens descontadas
         */
        public BigDecimal deductedPacks() {
            return packsBefore.subtract(packsAfter);
        }
    }

    /**
     * Monta o plano.
     *
     * @param recipe      receita (linha do Node: {@code yield_units} e {@code items})
     * @param ingredients ingredientes da dona por id (linha do Node: nome, unidade, pacote e custo)
     * @param packs       estoque de cada ingrediente, em embalagens
     * @param qty         unidades a produzir
     * @return o plano
     */
    public static ProductionPlan of(Map<String, Object> recipe, Map<Long, Map<String, Object>> ingredients,
            Map<Long, BigDecimal> packs, BigDecimal qty) {
        BigDecimal yield = number(recipe.get("yield_units"));
        BigDecimal ratio = yield.signum() > 0 ? qty.divide(yield, 10, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode item : items(recipe)) {
            long id = item.get("ingredient_id").asLong();
            Map<String, Object> ing = ingredients.get(id);
            if (ing == null) {
                continue;
            }
            BigDecimal needed = new BigDecimal(item.get("qty").asText()).multiply(ratio);
            Line line = line(id, ing, needed, packs.getOrDefault(id, BigDecimal.ZERO));
            lines.add(line);
            total = total.add(line.cost());
        }
        return new ProductionPlan(lines, total.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Uma linha do plano.
     *
     * @param id     ingrediente
     * @param ing    linha do ingrediente
     * @param needed quantidade necessária
     * @param packs  estoque de antes, em embalagens
     * @return a linha
     */
    private static Line line(long id, Map<String, Object> ing, BigDecimal needed, BigDecimal packs) {

        // ── 1. Quantidade exata e custo por unidade do ingrediente.
        BigDecimal exact = needed.setScale(3, RoundingMode.HALF_UP);
        BigDecimal size = number(ing.get("package_size"));
        BigDecimal perPack = size.signum() > 0 ? size : BigDecimal.ONE;
        BigDecimal unitCost = size.signum() > 0
                ? number(ing.get("package_cost")).divide(size, 10, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // ── 2. Estoque em g/ml/un, o que sobra (nunca negativo) e de volta em embalagens.
        BigDecimal stock = packs.multiply(perPack);
        BigDecimal after = stock.subtract(exact).max(BigDecimal.ZERO);
        BigDecimal packsAfter = after.divide(perPack, 3, RoundingMode.HALF_UP).min(packs);
        return new Line(id, String.valueOf(ing.get("name")), String.valueOf(ing.get("unit")), exact, stock, after,
                exact.subtract(stock).max(BigDecimal.ZERO), exact.multiply(unitCost), packs, packsAfter);
    }

    /**
     * Ingredientes que não cobrem a produção.
     *
     * @return as linhas com falta
     */
    public List<Line> shortages() {
        return lines.stream().filter(l -> l.missing().signum() > 0).toList();
    }

    /**
     * Itens da receita.
     *
     * @param recipe a receita
     * @return a lista (vazia se não houver)
     */
    private static JsonNode items(Map<String, Object> recipe) {
        return recipe.get("items") instanceof JsonNode node ? node : MissingNode.getInstance();
    }

    /**
     * Número de uma coluna do Node.
     *
     * @param value valor (nulo = 0)
     * @return o número exato
     */
    private static BigDecimal number(Object value) {
        return value instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    }
}
