package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
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
import org.springframework.transaction.annotation.Transactional;

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

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /** Campos do gasto fixo que o histórico guarda. */
    private static final List<String> COST = List.of("name", "kind", "amount", "category_id");

    /** Campos dos ajustes que o histórico guarda: mexem no custo e no preço de todas as receitas. */
    private static final List<String> SETTINGS = List.of("hourly_rate", "monthly_units", "min_margin_pct");

    /**
     * Cria os casos de uso.
     *
     * @param categories categorias
     * @param fixedCosts gastos fixos
     * @param settings   ajustes
     * @param audit      histórico de auditoria
     */
    public SetupUseCases(Categories categories, FixedCosts fixedCosts, OwnerSettings settings, AuditTrail audit) {
        this.categories = categories;
        this.fixedCosts = fixedCosts;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Categorias, filtradas por um tipo válido (tipo inválido = todas).
     *
     * @param owner dona
     * @param kind  tipo pedido (pode ser nulo)
     * @return as categorias
     */
    public List<Map<String, Object>> categories(OwnerId owner, String kind) {
        return categories.list(owner, kind != null && SimpleForms.CATEGORY_KINDS.contains(kind) ? kind : null);
    }

    /**
     * Cria uma categoria.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada
     */
    @Transactional
    public Map<String, Object> createCategory(OwnerId owner, Map<String, Object> body) {
        String kind = SimpleForms.categoryKind(body);
        Map<String, Object> row = categories.create(owner, kind, SimpleForms.category(body));
        audit.record(owner, Entry.of("cardapio", "criou", "category", row.get("id"), null, row,
                "Categoria criada: " + row.get("name")));
        return row;
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
    @Transactional
    public Map<String, Object> updateCategory(OwnerId owner, long id, Map<String, Object> body) {
        Map<String, Object> was = byId(categories.list(owner, null), id);
        Map<String, Object> row = categories.update(owner, id, SimpleForms.category(body))
                .orElseThrow(() -> new NotFoundException("Categoria não encontrada."));
        audit.record(owner, Entry.of("cardapio", "editou", "category", id, was, row,
                "Categoria editada: " + row.get("name")));
        return row;
    }

    /**
     * Apaga uma categoria.
     *
     * @param owner dona
     * @param id    categoria
     */
    @Transactional
    public void deleteCategory(OwnerId owner, long id) {
        Map<String, Object> was = byId(categories.list(owner, null), id);
        categories.delete(owner, id);
        audit.record(owner, Entry.of("cardapio", "excluiu", "category", id, was, null,
                "Categoria excluída: " + (was == null ? "#" + id : was.get("name"))));
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
    @Transactional
    public Map<String, Object> createFixedCost(OwnerId owner, Map<String, Object> body) {
        Map<String, Object> row = fixedCosts.create(owner, SimpleForms.fixedCost(body));
        audit.record(owner, Entry.of("financeiro", "criou", "fixed_cost", row.get("id"), null,
                AuditValues.pick(row, COST), "Gasto criado: " + row.get("name") + " (R$ "
                        + AuditValues.num(row.get("amount")) + ")"));
        return row;
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
    @Transactional
    public Map<String, Object> updateFixedCost(OwnerId owner, long id, Map<String, Object> body) {
        Map<String, Object> was = byId(fixedCosts.list(owner), id);
        Map<String, Object> row = fixedCosts.update(owner, id, SimpleForms.fixedCost(body))
                .orElseThrow(() -> new NotFoundException("Gasto não encontrado."));
        audit.record(owner, Entry.of("financeiro", "editou", "fixed_cost", id, AuditValues.pick(was, COST),
                AuditValues.pick(row, COST), "Gasto " + row.get("name") + ": R$ "
                        + AuditValues.num(was == null ? null : was.get("amount")) + " para R$ "
                        + AuditValues.num(row.get("amount"))));
        return row;
    }

    /**
     * Apaga um gasto.
     *
     * @param owner dona
     * @param id    gasto
     */
    @Transactional
    public void deleteFixedCost(OwnerId owner, long id) {
        Map<String, Object> was = byId(fixedCosts.list(owner), id);
        fixedCosts.delete(owner, id);
        audit.record(owner, Entry.of("financeiro", "excluiu", "fixed_cost", id, AuditValues.pick(was, COST), null,
                "Gasto excluído: " + (was == null ? "#" + id : was.get("name"))));
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
    @Transactional
    public Map<String, Object> saveSettings(OwnerId owner, Map<String, Object> body) {

        // ── 1. Como os ajustes estavam.
        Map<String, Object> was = settings(owner);

        // ── 2. Grava e compara: valor da hora e unidades por mês mudam o custo de todas as receitas.
        Map<String, Object> row = settings.save(owner, SimpleForms.settings(body));
        Map<String, Object> now = AuditValues.pick(row, SETTINGS);
        if (AuditValues.changed(was, now, SETTINGS)) {
            audit.record(owner, Entry.of("preco", "editou", "settings", owner.value(), AuditValues.pick(was, SETTINGS),
                    now, "Ajustes de custo: hora R$ " + AuditValues.num(was.get("hourly_rate")) + " para R$ "
                            + AuditValues.num(now.get("hourly_rate")) + ", unidades/mês "
                            + AuditValues.num(was.get("monthly_units")) + " para "
                            + AuditValues.num(now.get("monthly_units")) + ", margem mínima "
                            + AuditValues.num(was.get("min_margin_pct")) + "% para "
                            + AuditValues.num(now.get("min_margin_pct")) + "%"));
        }
        return row;
    }

    /**
     * A linha pelo id.
     *
     * @param rows linhas da dona
     * @param id   id procurado
     * @return a linha, ou {@code null}
     */
    private static Map<String, Object> byId(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(r -> r.get("id") instanceof Number n && n.longValue() == id).findFirst()
                .orElse(null);
    }
}
