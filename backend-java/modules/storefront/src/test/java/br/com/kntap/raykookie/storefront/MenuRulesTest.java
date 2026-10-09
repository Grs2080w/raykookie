package br.com.kntap.raykookie.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.storefront.api.Menu;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.domain.MenuBuilder;
import br.com.kntap.raykookie.storefront.domain.PickupForm;
import br.com.kntap.raykookie.storefront.domain.StoryRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras puras da vitrine, com as saídas geradas no Node ({@code localeCompare('pt-BR')}, {@code slug},
 * {@code weightLabel}).
 */
class MenuRulesTest {

    /**
     * Uma receita do cardápio.
     *
     * @param id       id
     * @param name     nome
     * @param category categoria
     * @return a receita
     */
    private static MenuBuilder.Recipe recipe(long id, String name, String category) {
        return new MenuBuilder.Recipe(id, name, 10, category, 0, null, List.of(), true);
    }

    /**
     * Ordem dos grupos idêntica ao Node, com acentos e maiúsculas.
     */
    @Test
    @DisplayName("grupos na ordem do localeCompare pt-BR, Outros por último")
    void groupOrder() {
        List<String> names = List.of("Outros", "Zebra", "Éclairs", "açaí", "Açaí", "Bolos", "bolos de pote", "Ábacos",
                "Café", "Brigadeiros", "Cafés", "Bolo", "Doces finos", "doces");
        List<MenuBuilder.Recipe> recipes = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            recipes.add(recipe(i + 1, "Item " + i, names.get(i)));
        }
        Menu menu = MenuBuilder.build(recipes);
        assertThat(menu.groups()).extracting(Menu.Group::name).containsExactly("Ábacos", "açaí", "Açaí", "Bolo",
                "Bolos", "bolos de pote", "Brigadeiros", "Café", "Cafés", "doces", "Doces finos", "Éclairs", "Zebra",
                "Outros");
        assertThat(menu.groups().getFirst().id()).isEqualTo("abacos");
    }

    /**
     * Filtro, categoria padrão, itens por nome e campos opcionais.
     */
    @Test
    @DisplayName("só preço > 0 e visível; sem categoria vira Outros; itens por nome; campos opcionais")
    void filtersAndFields() {
        Menu menu = MenuBuilder.build(List.of(
                new MenuBuilder.Recipe(1, "Pão", 5, null, 250, "123", List.of("vegano"), true),
                new MenuBuilder.Recipe(2, "Bolo", 0, "Bolos", 0, null, List.of(), true),
                new MenuBuilder.Recipe(3, "Torta", 9, "Bolos", 0, null, List.of(), false),
                new MenuBuilder.Recipe(4, "Açúcar", 7, "", 0, "", List.of(), true)));
        assertThat(menu.items()).extracting(Menu.Item::id).containsExactly("1", "4");
        assertThat(menu.groups()).hasSize(1);
        assertThat(menu.groups().getFirst().items()).extracting(Menu.Item::name).containsExactly("Açúcar", "Pão");
        Map<String, Object> bread = menu.items().getFirst().json();
        assertThat(bread).containsEntry("weight_g", 250L).containsEntry("image", "/api/fotos/1?v=123")
                .containsEntry("tags", List.of("vegano")).containsEntry("price", 5L);
        assertThat(menu.items().get(1).json()).doesNotContainKeys("weight_g", "image", "tags");
        assertThat(menu.items().getFirst().money().cents()).isEqualTo(500);
        assertThat(menu.json()).containsKeys("items", "groups");
        assertThat(MenuBuilder.build(List.of()).groups()).isEmpty();
    }

    /**
     * Textos do story iguais aos do Node.
     */
    @Test
    @DisplayName("peso e preço como o Node escreve")
    void labels() {
        assertThat(List.of(250.0, 1250.0, 1000.0, 0.5, 999.999, 12.345).stream().map(StoryRenderer::weightLabel))
                .containsExactly("250 g", "1,25 kg", "1 kg", "0,5 g", "1000 g", "12,35 g");
        assertThat(StoryRenderer.weightLabel(0)).isEmpty();
    }

    /**
     * Formulário do endereço de retirada.
     */
    @Test
    @DisplayName("endereço de retirada: CEP, obrigatórios, apelido padrão e linha do pedido")
    void pickupForm() {
        PickupForm form = PickupForm.from(Map.of("street", "Rua A", "number", "1", "district", "Centro", "city", "SP",
                "cep", "01.234-567", "instructions", "Portão azul"));
        assertThat(form.label()).isEqualTo("Rua A");
        assertThat(form.cep()).isEqualTo("01234567");
        assertThatThrownBy(() -> PickupForm.from(Map.of("street", "Rua A", "number", "1", "district", "C",
                "cep", "123"))).isInstanceOf(InvalidInputException.class).hasMessage("O CEP precisa ter 8 números.");
        assertThatThrownBy(() -> PickupForm.from(Map.of("street", "Rua A", "number", "1", "district", "C")))
                .hasMessage("Informe a cidade.");
        PickupAddress address = new PickupAddress(1, "x", "", "Rua A", "1", "", "Centro", "SP", "Portão azul", true);
        assertThat(address.pickupLine()).isEqualTo("Rua A, 1 - Centro, SP · Portão azul");
        assertThat(new PickupAddress(1, "x", "", "Rua A", "1", "fundos", "Centro", "SP", "", true).pickupLine())
                .isEqualTo("Rua A, 1 (fundos) - Centro, SP");
    }
}
