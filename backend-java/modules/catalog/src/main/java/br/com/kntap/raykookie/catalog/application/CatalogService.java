package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.catalog.api.MenuCombo;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.application.ports.Categories;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.catalog.application.ports.PhotoStore;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * O catálogo visto pelos outros módulos.
 */
@Service
public class CatalogService implements CatalogApi {

    /** Preço das receitas. */
    private final RecipePricing pricing;

    /** Categorias. */
    private final Categories categories;

    /** Ingredientes. */
    private final Ingredients ingredients;

    /** Histórico e alertas de preço. */
    private final PriceImpact priceImpact;

    /** Fotos das receitas. */
    private final PhotoStore photos;

    /** Combos. */
    private final ComboUseCases combos;

    /**
     * Cria o serviço.
     *
     * @param pricing     preço das receitas
     * @param categories  categorias
     * @param ingredients ingredientes
     * @param priceImpact histórico e alertas de preço
     * @param photos      fotos das receitas
     * @param combos      combos
     */
    public CatalogService(RecipePricing pricing, Categories categories, Ingredients ingredients,
            PriceImpact priceImpact, PhotoStore photos, ComboUseCases combos) {
        this.pricing = pricing;
        this.categories = categories;
        this.ingredients = ingredients;
        this.priceImpact = priceImpact;
        this.photos = photos;
        this.combos = combos;
    }

    /** {@inheritDoc} */
    @Override
    public List<PricedRecipe> pricedRecipes(OwnerId owner) {
        return pricing.priced(owner);
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> categories(OwnerId owner, String kind) {
        return categories.list(owner, kind);
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> ingredients(OwnerId owner) {
        return ingredients.list(owner);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RecipePhoto> photo(OwnerId owner, long recipeId) {
        return photos.load(recipeId, owner.value()).map(found -> new RecipePhoto(found.contentType(), found.data()));
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> createIngredient(OwnerId owner, IngredientForm form) {
        return ingredients.create(owner, form);
    }

    /** {@inheritDoc} */
    @Override
    public int priceChanged(PriceChange change) {
        return priceImpact.priceChanged(change);
    }

    /** {@inheritDoc} */
    @Override
    public List<MenuCombo> menuCombos(OwnerId owner) {
        return combos.menu(owner);
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, Double> comboCosts(OwnerId owner) {
        return combos.costs(owner);
    }
}
