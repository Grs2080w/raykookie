package br.com.kntap.raykookie.storefront.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
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

        // ── 3. Receitas precificadas viram o cardápio.
        List<MenuBuilder.Recipe> recipes = catalog.pricedRecipes(owner).stream()
                .map(recipe -> recipe(recipe, categories)).toList();
        return MenuBuilder.build(recipes);
    }

    /**
     * A receita vista pelo cardápio.
     *
     * @param recipe     receita precificada
     * @param categories nome das categorias
     * @return a receita
     */
    private static MenuBuilder.Recipe recipe(PricedRecipe recipe, Map<Long, String> categories) {
        String category = recipe.categoryId() == null ? null : categories.get(recipe.categoryId());
        return new MenuBuilder.Recipe(recipe.id(), recipe.name(), recipe.pricing().rounded(), category,
                recipe.unitWeightG(), recipe.photoVersion(), recipe.tags(), recipe.showOnMenu());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PickupAddress> activePickupAddress(OwnerId owner) {
        return pickups.active(owner);
    }
}
