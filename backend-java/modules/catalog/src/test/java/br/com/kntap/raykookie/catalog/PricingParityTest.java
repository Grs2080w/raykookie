package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.catalog.api.Packs;
import br.com.kntap.raykookie.catalog.api.PricingEngine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Paridade exata com o TypeScript ({@code rules-vectors/pricing.json}, gerado por
 * {@code infra/rules-vectors/gen-pricing.mjs}): cada campo do cálculo é o mesmo double, e o preço final os
 * mesmos centavos.
 */
class PricingParityTest {

    /** Campos do resultado, na ordem do Node. */
    private static final List<String> FIELDS = List.of("ingredients", "labor", "overhead", "total", "perUnit",
            "suggested", "rounded", "profitPerUnit", "profitTotal", "realMarginPct");

    /** O vetor inteiro. */
    private static JsonNode vectors;

    /**
     * Lê o vetor do classpath.
     *
     * @throws IOException se não ler
     */
    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = PricingParityTest.class.getResourceAsStream("/rules-vectors/pricing.json")) {
            vectors = new ObjectMapper().readTree(in);
        }
    }

    /**
     * Entrada do cálculo a partir do JSON.
     *
     * @param json a entrada
     * @return a entrada Java
     */
    private static PricingEngine.Input input(JsonNode json) {
        List<PricingEngine.Item> items = new ArrayList<>();
        json.get("items").forEach(item -> items.add(new PricingEngine.Item(item.get("qty").asDouble(),
                item.get("unitCost").asDouble())));
        return new PricingEngine.Input(items, json.get("yieldUnits").asDouble(), json.get("marginPct").asDouble(),
                json.get("prepHours").asDouble(), json.get("hourlyRate").asDouble(),
                json.get("fixedMonthly").asDouble(), json.get("monthlyUnits").asDouble());
    }

    /**
     * Confere campo a campo, com igualdade exata de double (0 e -0 contam como iguais, como no JSON).
     *
     * @param actual   resultado Java
     * @param expected resultado do TypeScript
     * @param where    identificação do caso
     */
    private static void assertSame(PricingEngine.Result actual, JsonNode expected, String where) {
        double[] values = {actual.ingredients(), actual.labor(), actual.overhead(), actual.total(), actual.perUnit(),
            actual.suggested(), actual.rounded(), actual.profitPerUnit(), actual.profitTotal(), actual.realMarginPct()};
        for (int i = 0; i < FIELDS.size(); i++) {
            double wanted = expected.get(FIELDS.get(i)).asDouble();
            assertThat(values[i] == wanted).as("%s.%s: %s ≠ %s", where, FIELDS.get(i), values[i], wanted).isTrue();
        }
        assertThat(actual.price().cents()).isEqualTo(Math.round(expected.get("rounded").asDouble() * 100));
    }

    /**
     * calcRecipe e withSalePrice: os 208 casos.
     */
    @Test
    @DisplayName("calcRecipe e withSalePrice idênticos ao TypeScript em todos os casos")
    void recipes() {
        int index = 0;
        for (JsonNode row : vectors.get("recipes")) {
            PricingEngine.Result result = PricingEngine.calculate(input(row.get("input")));
            assertSame(result, row.get("pricing"), "receita " + index);
            if (row.has("sale")) {
                assertSame(PricingEngine.withSalePrice(result, row.get("sale").asDouble()), row.get("withSale"),
                        "receita " + index + " com preço");
            }
            index++;
        }
        assertThat(index).isGreaterThanOrEqualTo(208);
    }

    /**
     * marginAlert: tipo e números iguais, inclusive quando não há alerta.
     */
    @Test
    @DisplayName("marginAlert idêntico ao TypeScript")
    void alerts() {
        for (JsonNode row : vectors.get("alerts")) {

            // ── 1. Antes e depois, com o preço fixado do caso.
            PricingEngine.Result before = PricingEngine.calculate(input(row.get("before")));
            PricingEngine.Result after = PricingEngine.calculate(input(row.get("after")));
            Double sale = row.get("sale").isNull() ? null : row.get("sale").asDouble();
            Optional<PricingEngine.MarginAlert> alert = PricingEngine.marginAlert(
                    PricingEngine.withSalePrice(before, sale), PricingEngine.withSalePrice(after, sale),
                    sale != null && sale > 0, row.get("minMarginPct").asDouble(), after.rounded());

            // ── 2. Mesmo resultado.
            JsonNode expected = row.get("alert");
            assertThat(alert.isPresent()).as(row.toString()).isEqualTo(!expected.isNull());
            alert.ifPresent(found -> {
                assertThat(found.kind()).isEqualTo(expected.get("kind").asString());
                assertThat(found.before()).isEqualTo(expected.get("before").asDouble());
                assertThat(found.after()).isEqualTo(expected.get("after").asDouble());
                assertThat(found.suggested()).isEqualTo(expected.get("suggested").asDouble());
            });
        }
    }

    /**
     * parsePack e pricePerLabel.
     */
    @Test
    @DisplayName("parsePack e pricePerLabel idênticos ao TypeScript")
    void packs() {
        for (JsonNode row : vectors.get("packs")) {
            Optional<Packs.Pack> pack = Packs.parse(row.get("name").asString());
            JsonNode expected = row.get("pack");
            assertThat(pack.isPresent()).as(row.toString()).isEqualTo(!expected.isNull());
            pack.ifPresent(found -> {
                assertThat(found.size()).as(row.toString()).isEqualTo(expected.get("size").asDouble());
                assertThat(found.unit()).isEqualTo(expected.get("unit").asString());
            });
        }
        for (JsonNode row : vectors.get("labels")) {
            assertThat(Packs.pricePerLabel(row.get("price").asDouble(), row.get("size").asDouble(),
                    row.get("unit").asString())).isEqualTo(row.get("label").asString());
        }
    }
}
