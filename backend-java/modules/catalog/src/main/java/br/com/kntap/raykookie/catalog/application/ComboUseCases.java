package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.api.MenuCombo;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.application.ports.Combos;
import br.com.kntap.raykookie.catalog.application.ports.OwnerSettings;
import br.com.kntap.raykookie.catalog.domain.ComboForm;
import br.com.kntap.raykookie.catalog.domain.ComboPricing;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Combos do cardápio: a página do Cardápio (lista, criar, editar, apagar e o desconto sugerido) e o que o cardápio
 * público e as métricas usam.
 */
@Service
public class ComboUseCases {

    /** Combo que não é da dona. */
    static final String NOT_FOUND = "Combo não encontrado.";

    /** Maior desconto sugerido aceito (%). */
    static final double MAX_DISCOUNT = 90;

    /** Combos. */
    private final Combos combos;

    /** Receitas precificadas. */
    private final RecipePricing pricing;

    /** Ajustes (margem mínima). */
    private final OwnerSettings settings;

    /**
     * Cria os casos de uso.
     *
     * @param combos   combos
     * @param pricing  receitas precificadas
     * @param settings ajustes da dona
     */
    public ComboUseCases(Combos combos, RecipePricing pricing, OwnerSettings settings) {
        this.combos = combos;
        this.pricing = pricing;
        this.settings = settings;
    }

    /**
     * Tudo que a seção Combos da página do Cardápio precisa: os produtos (preço e custo, para o preço sugerido ao
     * vivo), o desconto sugerido, a margem mínima e os combos calculados.
     *
     * @param owner dona
     * @return {@code discount_pct}, {@code min_margin_pct}, {@code products} e {@code combos}
     */
    public Map<String, Object> page(OwnerId owner) {

        // ── 1. Produtos e ajustes.
        Map<Long, PricedRecipe> products = products(owner);
        double discount = combos.discountPct(owner);
        double minMargin = minMargin(owner);

        // ── 2. Os combos calculados.
        List<Map<String, Object>> list = new ArrayList<>();
        for (Combos.Combo combo : combos.list(owner)) {
            list.add(comboJson(combo, products, discount, minMargin));
        }

        // ── 3. Tudo junto.
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("discount_pct", discount);
        page.put("min_margin_pct", minMargin);
        page.put("products", products.values().stream().map(ComboUseCases::productJson).toList());
        page.put("combos", list);
        return page;
    }

    /**
     * Cria (id nulo) ou edita um combo.
     *
     * @param owner dona
     * @param id    combo (nulo = novo)
     * @param body  {@code name}, {@code price} (vazio = o sugerido), {@code show_on_menu} e {@code items}
     * @return a página atualizada
     * @throws InvalidInputException com campo inválido ou produto que não é da dona
     */
    @Transactional
    public Map<String, Object> save(OwnerId owner, Long id, Map<String, Object> body) {

        // ── 1. Formulário válido, só com produtos da dona.
        ComboForm form = ComboForm.from(body);
        Set<Long> owned = products(owner).keySet();
        if (!owned.containsAll(form.recipeIds())) {
            throw new InvalidInputException("Algum produto do combo não foi encontrado nas suas receitas.");
        }

        // ── 2. Grava.
        if (id == null) {
            combos.create(owner, form);
        } else if (!combos.update(owner, id, form)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return page(owner);
    }

    /**
     * Apaga um combo (pedidos antigos continuam com o nome e o valor).
     *
     * @param owner dona
     * @param id    combo
     * @return a página atualizada
     */
    public Map<String, Object> delete(OwnerId owner, long id) {
        combos.delete(owner, id);
        return page(owner);
    }

    /**
     * Troca o desconto sugerido dos combos.
     *
     * @param owner dona
     * @param body  {@code discount_pct} (0 a 90)
     * @return a página atualizada
     * @throws InvalidInputException fora da faixa
     */
    public Map<String, Object> setDiscount(OwnerId owner, Map<String, Object> body) {
        double pct = NodeInput.number(body.get("discount_pct"), -1);
        if (pct < 0 || pct > MAX_DISCOUNT) {
            throw new InvalidInputException("O desconto sugerido dos combos vai de 0% a 90%.");
        }
        combos.setDiscountPct(owner, pct);
        return page(owner);
    }

    /**
     * Combos que podem ser vendidos agora, para o cardápio.
     *
     * @param owner dona
     * @return os combos
     */
    public List<MenuCombo> menu(OwnerId owner) {

        // ── 1. Produtos e ajustes.
        Map<Long, PricedRecipe> products = products(owner);
        double discount = combos.discountPct(owner);
        double minMargin = minMargin(owner);

        // ── 2. Só os vendáveis, com o resumo, a foto e os selos.
        List<MenuCombo> out = new ArrayList<>();
        for (Combos.Combo combo : combos.list(owner)) {
            ComboPricing.Result r = calc(combo, products, discount, minMargin);
            if (unavailable(combo, products, r) == null) {
                out.add(menuCombo(combo, products, r));
            }
        }
        return out;
    }

    /**
     * Custo atual de cada combo (lucro nas métricas).
     *
     * @param owner dona
     * @return combo → custo
     */
    public Map<Long, Double> costs(OwnerId owner) {
        Map<Long, PricedRecipe> products = products(owner);
        Map<Long, Double> costs = new LinkedHashMap<>();
        for (Combos.Combo combo : combos.list(owner)) {
            costs.put(combo.id(), calc(combo, products, 0, 0).cost());
        }
        return costs;
    }

    /**
     * Receitas precificadas da dona, por id.
     *
     * @param owner dona
     * @return id → receita
     */
    private Map<Long, PricedRecipe> products(OwnerId owner) {
        return pricing.priced(owner).stream()
                .collect(Collectors.toMap(PricedRecipe::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Margem mínima da dona.
     *
     * @param owner dona
     * @return a margem (%)
     */
    private double minMargin(OwnerId owner) {
        return settings.find(owner).map(OwnerSettings.Values::minMarginPct)
                .orElse(OwnerSettings.Values.DEFAULTS.minMarginPct());
    }

    /**
     * Calcula o combo com os preços e custos atuais dos produtos.
     *
     * @param combo     combo
     * @param products  produtos
     * @param discount  desconto sugerido
     * @param minMargin margem mínima
     * @return o cálculo
     */
    private static ComboPricing.Result calc(Combos.Combo combo, Map<Long, PricedRecipe> products, double discount,
            double minMargin) {
        List<ComboPricing.Part> parts = new ArrayList<>();
        for (ComboForm.Item item : combo.items()) {
            PricedRecipe p = products.get(item.recipeId());
            if (p != null) {
                parts.add(new ComboPricing.Part(item.qty(), p.pricing().rounded(), p.pricing().perUnit()));
            }
        }
        return ComboPricing.calc(parts, discount, minMargin, combo.price());
    }

    /**
     * Por que o combo não aparece no cardápio.
     *
     * @param combo    combo
     * @param products produtos
     * @param r        cálculo
     * @return o motivo, ou nulo se aparece
     */
    private static String unavailable(Combos.Combo combo, Map<Long, PricedRecipe> products, ComboPricing.Result r) {
        if (!combo.showOnMenu()) {
            return "Desligado do cardápio.";
        }
        for (ComboForm.Item item : combo.items()) {
            PricedRecipe p = products.get(item.recipeId());
            if (p == null || !p.showOnMenu() || p.pricing().rounded() <= 0) {
                return "Algum produto deste combo está fora do cardápio ou sem preço.";
            }
        }
        return r.price() > 0 ? null : "Sem preço.";
    }

    /**
     * O combo na página do Cardápio.
     *
     * @param combo     combo
     * @param products  produtos
     * @param discount  desconto sugerido
     * @param minMargin margem mínima
     * @return o JSON
     */
    private static Map<String, Object> comboJson(Combos.Combo combo, Map<Long, PricedRecipe> products,
            double discount, double minMargin) {

        // ── 1. Dados gravados e itens com o nome do produto.
        ComboPricing.Result r = calc(combo, products, discount, minMargin);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", combo.id());
        json.put("name", combo.name());
        json.put("price", combo.price());
        json.put("show_on_menu", combo.showOnMenu());
        json.put("items", combo.items().stream().map(item -> Map.<String, Object>of("recipe_id", item.recipeId(),
                "qty", item.qty(), "name", products.containsKey(item.recipeId())
                        ? products.get(item.recipeId()).name() : "Produto apagado")).toList());

        // ── 2. Cálculo e disponibilidade.
        json.put("full_price", r.fullPrice());
        json.put("cost", r.cost());
        json.put("suggested", r.suggested());
        json.put("final_price", r.price());
        json.put("profit", r.profit());
        json.put("margin_pct", r.marginPct());
        json.put("below_cost", r.belowCost());
        String why = unavailable(combo, products, r);
        json.put("available", why == null);
        json.put("unavailable_reason", why);
        return json;
    }

    /**
     * Um produto para o editor de combos.
     *
     * @param p produto
     * @return {@code id}, {@code name}, {@code price}, {@code cost}, {@code photo_v} e {@code on_menu}
     */
    private static Map<String, Object> productJson(PricedRecipe p) {

        // ── 1. Quem é o produto e o preço avulso.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", p.id());
        json.put("name", p.name());
        json.put("price", p.pricing().rounded());

        // ── 2. Custo por unidade (da receita padrão), foto e se está no cardápio.
        json.put("cost", Math.round(p.pricing().perUnit() * 100) / 100.0);
        json.put("photo_v", p.photoVersion());
        json.put("on_menu", p.showOnMenu() && p.pricing().rounded() > 0);
        return json;
    }

    /**
     * O combo para o cardápio público.
     *
     * @param combo    combo
     * @param products produtos
     * @param r        cálculo
     * @return o combo
     */
    private static MenuCombo menuCombo(Combos.Combo combo, Map<Long, PricedRecipe> products, ComboPricing.Result r) {

        // ── 1. Produtos, selos somados e a foto do primeiro produto que tiver foto.
        List<MenuCombo.Part> parts = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        Long photoRecipe = null;
        String photoVersion = null;
        for (ComboForm.Item item : combo.items()) {
            PricedRecipe p = products.get(item.recipeId());
            parts.add(new MenuCombo.Part(p.id(), p.name(), item.qty()));
            tags.addAll(p.tags());
            if (photoRecipe == null && p.photoVersion() != null) {
                photoRecipe = p.id();
                photoVersion = p.photoVersion();
            }
        }

        // ── 2. O resumo ("4 Cookie + 2 Brownie") e o combo pronto para o cardápio.
        String contents = parts.stream().map(part -> part.qty() + " " + part.name())
                .collect(Collectors.joining(" + "));
        return new MenuCombo(combo.id(), combo.name(), r.price(), r.fullPrice(), contents, parts, photoRecipe,
                photoVersion, List.copyOf(tags));
    }
}
