package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.application.ports.Categories;
import br.com.kntap.raykookie.catalog.application.ports.OwnerSettings;
import br.com.kntap.raykookie.catalog.domain.DefaultCategories;
import br.com.kntap.raykookie.identity.api.events.OwnerRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Conta nova: cria os ajustes e as categorias padrão na mesma transação do cadastro (como o
 * {@code registerUser} do Node).
 */
@Component
public class OwnerDefaults {

    /** Ajustes. */
    private final OwnerSettings settings;

    /** Categorias. */
    private final Categories categories;

    /**
     * Cria o ouvinte.
     *
     * @param settings   ajustes
     * @param categories categorias
     */
    public OwnerDefaults(OwnerSettings settings, Categories categories) {
        this.settings = settings;
        this.categories = categories;
    }

    /**
     * Cria os padrões da conta nova.
     *
     * @param event cadastro da dona
     */
    @EventListener
    public void onOwnerRegistered(OwnerRegistered event) {
        settings.createDefaults(event.ownerId());
        categories.createAll(event.ownerId(), DefaultCategories.ALL);
    }
}
