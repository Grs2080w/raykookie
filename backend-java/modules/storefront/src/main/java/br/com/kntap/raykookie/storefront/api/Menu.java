package br.com.kntap.raykookie.storefront.api;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cardápio da loja: só nome, preço, categoria, peso, foto e selos (nada de custo ou margem).
 *
 * @param items  itens, na ordem das receitas
 * @param groups grupos por categoria, com "Outros" por último
 */
public record Menu(List<Item> items, List<Group> groups) {

    /** Cardápio vazio (loja sem dona ou sem receitas à venda). */
    public static final Menu EMPTY = new Menu(List.of(), List.of());

    /** Prefixo do id dos combos no cardápio ({@code combo-<id>}). */
    public static final String COMBO_PREFIX = "combo-";

    /**
     * O cardápio no formato do Node.
     *
     * @return o mapa para o JSON
     */
    public Map<String, Object> json() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("items", items.stream().map(Item::json).toList());
        json.put("groups", groups.stream().map(Group::json).toList());
        return json;
    }

    /**
     * Item do cardápio.
     *
     * @param id       id da receita (em texto, como no Node)
     * @param name     nome
     * @param price    preço por unidade (múltiplo de R$ 0,50 ou o fixado)
     * @param category nome da categoria ("Outros" se não houver)
     * @param weightG  gramas de cada unidade (0 = não informado)
     * @param image    caminho da foto com a versão, ou nulo
     * @param tags        selos
     * @param ingredients ingredientes da receita padrão (vazio = não mostra)
     * @param combo       o que vem no combo (nulo = produto avulso)
     */
    public record Item(String id, String name, double price, String category, double weightG, String image,
            List<String> tags, List<String> ingredients, Combo combo) {

        /**
         * Produto avulso, sem lista de ingredientes.
         *
         * @param id       id
         * @param name     nome
         * @param price    preço
         * @param category categoria
         * @param weightG  gramas de cada unidade
         * @param image    foto
         * @param tags     selos
         */
        public Item(String id, String name, double price, String category, double weightG, String image,
                List<String> tags) {
            this(id, name, price, category, weightG, image, tags, List.of(), null);
        }

        /**
         * Diz se é um combo.
         *
         * @return verdadeiro se for
         */
        public boolean isCombo() {
            return combo != null;
        }

        /**
         * Preço em centavos.
         *
         * @return o preço
         */
        public Money money() {
            return Money.ofCents(Math.round(price * 100));
        }

        /**
         * O item no formato do Node (campos opcionais só quando há valor).
         *
         * @return o mapa para o JSON
         */
        public Map<String, Object> json() {

            // ── 1. Campos que todo item tem.
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("name", name);
            json.put("price", NodeRows.number(price));
            json.put("category", category);

            // ── 2. Peso, foto e selos só quando existem (como no Node).
            if (weightG > 0) {
                json.put("weight_g", NodeRows.number(weightG));
            }
            if (image != null) {
                json.put("image", image);
            }
            if (!tags.isEmpty()) {
                json.put("tags", tags);
            }

            // ── 3. Ingredientes e combo, quando há.
            if (!ingredients.isEmpty()) {
                json.put("ingredients", ingredients);
            }
            if (combo != null) {
                json.put("combo", combo.json(price));
            }
            return json;
        }
    }

    /**
     * O que vem num combo.
     *
     * @param comboId   id do combo
     * @param contents  resumo ("4 Cookie + 2 Brownie")
     * @param fullPrice soma dos preços dos produtos avulsos
     * @param parts     produtos e quantidades
     */
    public record Combo(long comboId, String contents, double fullPrice, List<Part> parts) {

        /**
         * O combo no JSON do cardápio.
         *
         * @param price preço do combo
         * @return {@code contents}, {@code full_price}, {@code savings} e {@code parts}
         */
        public Map<String, Object> json(double price) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("contents", contents);
            json.put("full_price", NodeRows.number(fullPrice));
            json.put("savings", NodeRows.number(Math.max(0, Math.round((fullPrice - price) * 100) / 100.0)));
            json.put("parts", parts.stream().map(part -> Map.<String, Object>of("id", String.valueOf(part.recipeId()),
                    "name", part.name(), "qty", part.qty())).toList());
            return json;
        }
    }

    /**
     * Um produto dentro do combo.
     *
     * @param recipeId produto
     * @param name     nome
     * @param qty      quantidade
     */
    public record Part(long recipeId, String name, int qty) {
    }

    /**
     * Grupo do cardápio.
     *
     * @param id    nome sem acento, em minúsculas e com hífens
     * @param name  nome da categoria
     * @param items itens por nome
     */
    public record Group(String id, String name, List<Item> items) {

        /**
         * O grupo no formato do Node.
         *
         * @return o mapa para o JSON
         */
        public Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("name", name);
            json.put("items", items.stream().map(Item::json).toList());
            return json;
        }
    }
}
