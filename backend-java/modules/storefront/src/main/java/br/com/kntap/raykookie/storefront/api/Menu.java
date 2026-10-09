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
     * @param tags     selos
     */
    public record Item(String id, String name, double price, String category, double weightG, String image,
            List<String> tags) {

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
            return json;
        }
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
