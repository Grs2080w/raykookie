package br.com.kntap.raykookie.storefront.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.storefront.api.Menu;
import br.com.kntap.raykookie.storefront.domain.StoryRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stories do cardápio e de um produto (PNG para postar no Instagram). Só mostram o que já está no cardápio.
 */
@Service
public class StoryUseCases {

    /** Cardápio. */
    private final StorefrontService storefront;

    /** Catálogo (foto do produto). */
    private final CatalogApi catalog;

    /** Dona da loja. */
    private final ShopOwner shopOwner;

    /** Endereço do cardápio sem "https://" (o botão "Peça em …"). */
    private final String host;

    /**
     * Cria os casos de uso.
     *
     * @param storefront cardápio
     * @param catalog    catálogo
     * @param shopOwner  dona da loja
     * @param siteUrl    endereço público do site ({@code SITE_URL})
     */
    public StoryUseCases(StorefrontService storefront, CatalogApi catalog, ShopOwner shopOwner,
            @Value("${rayka.site-url:https://raykookie.com.br}") String siteUrl) {
        this.storefront = storefront;
        this.catalog = catalog;
        this.shopOwner = shopOwner;
        this.host = siteUrl.replaceAll("/$", "").replaceFirst("^https?://", "") + "/cardapio";
    }

    /**
     * Story do cardápio.
     *
     * @return o PNG
     * @throws NotFoundException com o cardápio vazio
     */
    public byte[] menu() {
        Menu menu = storefront.currentMenu();
        if (menu.items().isEmpty()) {
            throw new NotFoundException("O cardápio está vazio.");
        }
        return StoryRenderer.menu(menu.items(), host);
    }

    /**
     * Story de um produto do cardápio.
     *
     * @param id id da receita (como na URL)
     * @return o PNG
     * @throws NotFoundException se o produto não estiver no cardápio
     */
    public byte[] product(String id) {
        Menu.Item item = storefront.currentMenu().items().stream().filter(found -> found.id().equals(id))
                .findFirst().orElseThrow(() -> new NotFoundException("Produto não encontrado no cardápio."));
        byte[] photo = catalog.photo(shopOwner.id(), Long.parseLong(id))
                .filter(found -> found.contentType().matches("image/(jpeg|png)"))
                .map(CatalogApi.RecipePhoto::data).orElse(null);
        return StoryRenderer.product(item, photo, host);
    }
}
