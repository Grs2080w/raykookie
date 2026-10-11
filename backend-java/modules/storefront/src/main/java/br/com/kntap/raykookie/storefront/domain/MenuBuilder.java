package br.com.kntap.raykookie.storefront.domain;

import br.com.kntap.raykookie.storefront.api.Menu;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monta o cardápio a partir das receitas (porte de {@code backend/lib/shop-menu.ts}): só receitas visíveis e
 * com preço, agrupadas por categoria em ordem alfabética pt-BR, com "Outros" por último.
 */
public final class MenuBuilder {

    /** Nome e id do grupo dos combos. */
    public static final String COMBOS = "Combos";

    /** Id do grupo dos combos. */
    static final String COMBOS_ID = "combos";

    /** Categoria de quem não tem categoria. */
    public static final String OTHERS = "Outros";

    /** Ordem alfabética do português ({@code localeCompare(…, 'pt-BR')}). */
    private static final Comparator<Object> PT_BR = Collator.getInstance(Locale.of("pt", "BR"));

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private MenuBuilder() {
    }

    /**
     * Monta o cardápio.
     *
     * @param recipes receitas já precificadas da dona
     * @return itens e grupos
     */
    public static Menu build(List<Recipe> recipes) {
        return build(recipes, List.of());
    }

    /**
     * Monta o cardápio com os combos, que vêm num grupo "Combos" antes das categorias.
     *
     * @param recipes receitas já precificadas da dona
     * @param combos  combos que podem ser vendidos, já como itens
     * @return itens e grupos
     */
    public static Menu build(List<Recipe> recipes, List<Menu.Item> combos) {

        // ── 1. Só o que pode ser vendido: preço maior que zero e visível no cardápio.
        List<Menu.Item> items = new ArrayList<>();
        for (Recipe recipe : recipes) {
            if (recipe.price() > 0 && recipe.showOnMenu()) {
                items.add(item(recipe));
            }
        }

        // ── 2. Grupos por categoria, na ordem da primeira aparição.
        Map<String, List<Menu.Item>> byCategory = new LinkedHashMap<>();
        for (Menu.Item item : items) {
            byCategory.computeIfAbsent(item.category(), key -> new ArrayList<>()).add(item);
        }

        // ── 3. Ordena os grupos ("Outros" por último) e os itens de cada grupo pelo nome.
        List<Menu.Group> groups = byCategory.entrySet().stream()
                .sorted((a, b) -> compareCategories(a.getKey(), b.getKey()))
                .map(entry -> new Menu.Group(slug(entry.getKey()), entry.getKey(), entry.getValue().stream()
                        .sorted((x, y) -> PT_BR.compare(x.name(), y.name())).toList()))
                .toList();

        // ── 4. Combos primeiro, num grupo só deles.
        if (combos.isEmpty()) {
            return new Menu(items, groups);
        }
        List<Menu.Item> sortedCombos = combos.stream().sorted((x, y) -> PT_BR.compare(x.name(), y.name())).toList();
        List<Menu.Item> allItems = new ArrayList<>(sortedCombos);
        allItems.addAll(items);
        List<Menu.Group> allGroups = new ArrayList<>();
        allGroups.add(new Menu.Group(COMBOS_ID, COMBOS, sortedCombos));
        allGroups.addAll(groups);
        return new Menu(allItems, allGroups);
    }

    /**
     * Item do cardápio de uma receita.
     *
     * @param recipe receita
     * @return o item
     */
    private static Menu.Item item(Recipe recipe) {
        String category = recipe.category() == null || recipe.category().isEmpty() ? OTHERS : recipe.category();
        String image = recipe.photoVersion() == null || recipe.photoVersion().isEmpty()
                ? null
                : "/api/fotos/" + recipe.id() + "?v=" + recipe.photoVersion();
        return new Menu.Item(String.valueOf(recipe.id()), recipe.name(), recipe.price(), category, recipe.weightG(),
                image, recipe.tags(), recipe.ingredients(), null);
    }

    /**
     * Ordem das categorias: alfabética, com "Outros" por último.
     *
     * @param a uma categoria
     * @param b outra
     * @return a comparação
     */
    static int compareCategories(String a, String b) {
        if (a.equals(OTHERS)) {
            return 1;
        }
        return b.equals(OTHERS) ? -1 : PT_BR.compare(a, b);
    }

    /**
     * Id do grupo: sem acento, minúsculas, hífens no lugar do resto ({@code slug}).
     *
     * @param name nome
     * @return o id ("outros" se ficar vazio)
     */
    static String slug(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return plain.isEmpty() ? "outros" : plain;
    }

    /**
     * Receita vista pelo cardápio.
     *
     * @param id           id
     * @param name         nome
     * @param price        preço de venda (arredondado ou fixado)
     * @param category     nome da categoria (nulo = nenhuma)
     * @param weightG      gramas por unidade
     * @param photoVersion versão da foto (nula = sem foto)
     * @param tags         selos
     * @param showOnMenu   se está visível
     * @param ingredients  nomes dos ingredientes da receita padrão (vazio = não mostra)
     */
    public record Recipe(long id, String name, double price, String category, double weightG, String photoVersion,
            List<String> tags, boolean showOnMenu, List<String> ingredients) {

        /**
         * Receita sem lista de ingredientes.
         *
         * @param id           id
         * @param name         nome
         * @param price        preço
         * @param category     categoria
         * @param weightG      gramas
         * @param photoVersion versão da foto
         * @param tags         selos
         * @param showOnMenu   se aparece no cardápio
         */
        public Recipe(long id, String name, double price, String category, double weightG, String photoVersion,
                List<String> tags, boolean showOnMenu) {
            this(id, name, price, category, weightG, photoVersion, tags, showOnMenu, List.of());
        }
    }
}
