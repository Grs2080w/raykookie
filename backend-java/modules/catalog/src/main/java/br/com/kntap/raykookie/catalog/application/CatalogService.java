package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.application.ports.Categories;
import br.com.kntap.raykookie.catalog.application.ports.Ingredients;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
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

    /**
     * Cria o serviço.
     *
     * @param pricing     preço das receitas
     * @param categories  categorias
     * @param ingredients ingredientes
     * @param priceImpact histórico e alertas de preço
     */
    public CatalogService(RecipePricing pricing, Categories categories, Ingredients ingredients,
            PriceImpact priceImpact) {
        this.pricing = pricing;
        this.categories = categories;
        this.ingredients = ingredients;
        this.priceImpact = priceImpact;
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
    public int priceChanged(PriceChange change) {
        return priceImpact.priceChanged(change);
    }
}
