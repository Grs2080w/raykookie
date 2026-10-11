package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.catalog.domain.ComboForm;
import br.com.kntap.raykookie.catalog.domain.ComboPricing;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Preço sugerido e formulário dos combos.
 */
class ComboPricingTest {

    /**
     * Desconto sobre a soma avulsa, arredondado para cima em R$ 0,50.
     */
    @Test
    @DisplayName("sugerido: soma avulsa menos o desconto, arredondado para cima em R$ 0,50")
    void discountOnFullPrice() {
        // 4 cookies de R$ 9 + 2 brownies de R$ 12 = R$ 60; 10% = R$ 54; custo R$ 20 (margem mínima 30% = R$ 26)
        ComboPricing.Result r = ComboPricing.calc(List.of(new ComboPricing.Part(4, 9, 3),
                new ComboPricing.Part(2, 12, 4)), 10, 30, null);
        assertThat(r.fullPrice()).isEqualTo(60.0);
        assertThat(r.cost()).isEqualTo(20.0);
        assertThat(r.suggested()).isEqualTo(54.0);
        assertThat(r.price()).isEqualTo(54.0);
        assertThat(r.profit()).isEqualTo(34.0);
        assertThat(r.marginPct()).isEqualTo(170.0);
        assertThat(ComboPricing.calc(List.of(new ComboPricing.Part(3, 7.30, 2)), 10, 30, null).suggested())
                .isEqualTo(20.0);
    }

    /**
     * Piso: custo mais a margem mínima.
     */
    @Test
    @DisplayName("piso: nunca abaixo do custo + margem mínima; preço escolhido vale e pode ficar abaixo do custo")
    void floorAndFixedPrice() {
        ComboPricing.Result floor = ComboPricing.calc(List.of(new ComboPricing.Part(2, 10, 8)), 50, 30, null);
        assertThat(floor.suggested()).isEqualTo(21.0);
        ComboPricing.Result fixed = ComboPricing.calc(List.of(new ComboPricing.Part(2, 10, 8)), 10, 30, 15.0);
        assertThat(fixed.price()).isEqualTo(15.0);
        assertThat(fixed.belowCost()).isTrue();
        assertThat(ComboPricing.calc(List.of(), 10, 30, null).marginPct()).isZero();
    }

    /**
     * Formulário.
     */
    @Test
    @DisplayName("formulário: nome, 2 unidades no mínimo, quantidades somadas, preço vazio = sugerido")
    void form() {
        ComboForm form = ComboForm.from(Map.of("name", "Festa", "price", "", "items", List.of(
                Map.of("recipe_id", 1, "qty", 1), Map.of("recipe_id", 1, "qty", 2), Map.of("recipe_id", "x", "qty", 3))));
        assertThat(form.items()).containsExactly(new ComboForm.Item(1, 3));
        assertThat(form.price()).isNull();
        assertThat(form.showOnMenu()).isTrue();
        assertThat(ComboForm.from(Map.of("name", "A", "price", 30, "show_on_menu", false,
                "items", List.of(Map.of("recipe_id", 2, "qty", 2)))).recipeIds()).containsExactly(2L);
        assertThatThrownBy(() -> ComboForm.from(Map.of("items", List.of())))
                .isInstanceOf(InvalidInputException.class).hasMessage("Dê um nome para o combo.");
        assertThatThrownBy(() -> ComboForm.from(Map.of("name", "A", "items", List.of(Map.of("recipe_id", 1,
                "qty", 1))))).hasMessage("Um combo precisa de pelo menos 2 unidades de produtos.");
        assertThatThrownBy(() -> ComboForm.from(Map.of("name", "A", "items", List.of(Map.of("recipe_id", 1,
                "qty", 100))))).hasMessage("A quantidade de cada produto vai de 1 a 99.");
        assertThat(ComboForm.from(Map.of("name", "A", "price", 0, "items", List.of(Map.of("recipe_id", 2,
                "qty", 2)))).price()).isNull();
    }
}
