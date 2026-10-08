package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.application.ports.Categories;
import br.com.kntap.raykookie.catalog.application.ports.FixedCosts;
import br.com.kntap.raykookie.catalog.application.ports.OwnerSettings;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Cadastros de apoio da dona: categorias, gastos fixos e ajustes.
 */
@Service
public class SetupUseCases {

    /** Categorias. */
    private final Categories categories;

    /** Gastos fixos. */
    private final FixedCosts fixedCosts;

    /** Ajustes. */
    private final OwnerSettings settings;

    /**
     * Cria os casos de uso.
     *
     * @param categories categorias
     * @param fixedCosts gastos fixos
     * @param settings   ajustes
     */
    public SetupUseCases(Categories categories, FixedCosts fixedCosts, OwnerSettings settings) {
        this.categories = categories;
        this.fixedCosts = fixedCosts;
        this.settings = settings;
    }

    /**
     * Categorias, filtradas por um tipo válido (tipo inválido = todas).
     *
     * @param owner dona
     * @param kind  tipo pedido (pode ser nulo)
     * @return as categorias
     */
    public List<Map<String, Object>> categories(OwnerId owner, String kind) {
        return categories.list(owner, SimpleForms.CATEGORY_KINDS.contains(kind) ? kind : null);
    }

    /**
     * Cria uma categoria.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada
     */
    public Map<String, Object> createCategory(OwnerId owner, Map<String, Object> body) {
        String kind = SimpleForms.categoryKind(body);
        return categories.create(owner, kind, SimpleForms.category(body));
    }

    /**
     * Edita uma categoria.
     *
     * @param owner dona
     * @param id    categoria
     * @param body  corpo JSON
     * @return a linha editada
     * @throws NotFoundException se não for da dona
     */
    public Map<String, Object> updateCategory(OwnerId owner, long id, Map<String, Object> body) {
        return categories.update(owner, id, SimpleForms.category(body))
                .orElseThrow(() -> new NotFoundException("Categoria não encontrada."));
    }

    /**
     * Apaga uma categoria.
     *
     * @param owner dona
     * @param id    categoria
     */
    public void deleteCategory(OwnerId owner, long id) {
        categories.delete(owner, id);
    }

    /**
     * Gastos fixos.
     *
     * @param owner dona
     * @return os gastos
     */
    public List<Map<String, Object>> fixedCosts(OwnerId owner) {
        return fixedCosts.list(owner);
    }

    /**
     * Cria um gasto.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada
     */
    public Map<String, Object> createFixedCost(OwnerId owner, Map<String, Object> body) {
        return fixedCosts.create(owner, SimpleForms.fixedCost(body));
    }

    /**
     * Edita um gasto.
     *
     * @param owner dona
     * @param id    gasto
     * @param body  corpo JSON
     * @return a linha editada
     * @throws NotFoundException se não for da dona
     */
    public Map<String, Object> updateFixedCost(OwnerId owner, long id, Map<String, Object> body) {
        return fixedCosts.update(owner, id, SimpleForms.fixedCost(body))
                .orElseThrow(() -> new NotFoundException("Gasto não encontrado."));
    }

    /**
     * Apaga um gasto.
     *
     * @param owner dona
     * @param id    gasto
     */
    public void deleteFixedCost(OwnerId owner, long id) {
        fixedCosts.delete(owner, id);
    }

    /**
     * Ajustes da dona (ou os padrões, se ainda não houver).
     *
     * @param owner dona
     * @return {@code hourly_rate}, {@code monthly_units} e {@code min_margin_pct}
     */
    public Map<String, Object> settings(OwnerId owner) {
        OwnerSettings.Values values = settings.find(owner).orElse(OwnerSettings.Values.DEFAULTS);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("hourly_rate", NodeRows.number(values.hourlyRate()));
        json.put("monthly_units", NodeRows.number(values.monthlyUnits()));
        json.put("min_margin_pct", NodeRows.number(values.minMarginPct()));
        return json;
    }

    /**
     * Grava os ajustes.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha gravada
     */
    public Map<String, Object> saveSettings(OwnerId owner, Map<String, Object> body) {
        return settings.save(owner, SimpleForms.settings(body));
    }
}
