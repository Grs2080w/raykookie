package br.com.kntap.raykookie.storefront.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.MenuCombo;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.storefront.api.Menu;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.api.StorefrontApi;
import br.com.kntap.raykookie.storefront.application.ports.PickupAddresses;
import br.com.kntap.raykookie.storefront.domain.MenuBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Cardápio da loja = receitas da dona ({@code getMenu}) e o endereço de retirada ativo.
 */
@Service
public class StorefrontService implements StorefrontApi {

    /** Catálogo. */
    private final CatalogApi catalog;

    /** Dona da loja. */
    private final ShopOwner shopOwner;

    /** Endereços de retirada. */
    private final PickupAddresses pickups;

    /**
     * Cria o serviço.
     *
     * @param catalog   catálogo
     * @param shopOwner dona da loja
     * @param pickups   endereços de retirada
     */
    public StorefrontService(CatalogApi catalog, ShopOwner shopOwner, PickupAddresses pickups) {
        this.catalog = catalog;
        this.shopOwner = shopOwner;
        this.pickups = pickups;
    }

    /** {@inheritDoc} */
    @Override
    public Menu currentMenu() {

        // ── 1. Sem dona (instalação nova), o cardápio é vazio.
        if (shopOwner.isPending()) {
            return Menu.EMPTY;
        }

        // ── 2. Nome das categorias de receita.
        OwnerId owner = shopOwner.id();
        Map<Long, String> categories = new HashMap<>();
        for (Map<String, Object> row : catalog.categories(owner, "recipe")) {
            categories.put(((Number) row.get("id")).longValue(), (String) row.get("name"));
        }

        // ── 3. Nome dos ingredientes (para os produtos que mostram a lista).
        Map<Long, String> ingredients = new HashMap<>();
        for (Map<String, Object> row : catalog.ingredients(owner)) {
            ingredients.put(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")));
        }

        // ── 4. Receitas precificadas e combos vendáveis viram o cardápio.
        List<MenuBuilder.Recipe> recipes = catalog.pricedRecipes(owner).stream()
                .map(recipe -> recipe(recipe, categories, ingredients)).toList();
        List<Menu.Item> combos = catalog.menuCombos(owner).stream().map(StorefrontService::combo).toList();
        return MenuBuilder.build(recipes, combos);
    }

    /**
     * O combo como item do cardápio ({@code combo-<id>}).
     *
     * @param combo combo vendável
     * @return o item
     */
    private static Menu.Item combo(MenuCombo combo) {
        String image = combo.photoRecipe() == null ? null
                : "/api/fotos/" + combo.photoRecipe() + "?v=" + combo.photoVersion();
        List<Menu.Part> parts = combo.parts().stream().map(p -> new Menu.Part(p.recipeId(), p.name(), p.qty()))
                .toList();
        return new Menu.Item(Menu.COMBO_PREFIX + combo.id(), combo.name(), combo.price(), MenuBuilder.COMBOS, 0,
                image, combo.tags(), List.of(), new Menu.Combo(combo.id(), combo.contents(), combo.fullPrice(),
                        parts));
    }

    /**
     * A receita vista pelo cardápio.
     *
     * @param recipe      receita precificada
     * @param categories  nome das categorias
     * @param ingredients nome dos ingredientes
     * @return a receita
     */
    private static MenuBuilder.Recipe recipe(PricedRecipe recipe, Map<Long, String> categories,
            Map<Long, String> ingredients) {
        String category = recipe.categoryId() == null ? null : categories.get(recipe.categoryId());
        List<String> names = recipe.showIngredients() ? recipe.ingredientIds().stream().distinct()
                .map(ingredients::get).filter(Objects::nonNull).toList() : List.of();
        return new MenuBuilder.Recipe(recipe.id(), recipe.name(), recipe.pricing().rounded(), category,
                recipe.unitWeightG(), recipe.photoVersion(), recipe.tags(), recipe.showOnMenu(), names);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PickupAddress> activePickupAddress(OwnerId owner) {
        return pickups.active(owner);
    }
}
