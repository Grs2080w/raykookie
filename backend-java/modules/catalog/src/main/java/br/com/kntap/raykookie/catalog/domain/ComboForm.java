package br.com.kntap.raykookie.catalog.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Formulário de combo.
 *
 * @param name       nome (até 60)
 * @param price      preço escolhido (nulo = o sugerido)
 * @param showOnMenu se aparece no cardápio
 * @param items      produtos e quantidades
 */
public record ComboForm(String name, Double price, boolean showOnMenu, List<Item> items) {

    /** Teto de cada quantidade. */
    static final int MAX_QTY = 99;

    /**
     * Lê e valida o corpo.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static ComboForm from(Map<String, Object> body) {

        // ── 1. Nome e preço.
        String name = NodeInput.cut(NodeInput.text(body.get("name")), 60);
        if (name.isEmpty()) {
            throw new InvalidInputException("Dê um nome para o combo.");
        }
        Object raw = body.get("price");
        Double price = raw == null || "".equals(raw) ? null : NodeInput.number(raw);

        // ── 2. Produtos: quantidades somadas por produto; pelo menos 2 unidades no total.
        List<Item> items = items(body.get("items"));
        if (items.stream().mapToInt(Item::qty).sum() < 2) {
            throw new InvalidInputException("Um combo precisa de pelo menos 2 unidades de produtos.");
        }
        boolean show = !(body.get("show_on_menu") instanceof Boolean flag) || flag;
        return new ComboForm(name, price == null || price == 0 ? null : price, show, items);
    }

    /**
     * Produtos do combo, somando as quantidades repetidas.
     *
     * @param raw valor de {@code items}
     * @return os itens
     * @throws InvalidInputException com quantidade fora de 1 a 99
     */
    private static List<Item> items(Object raw) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (Object element : raw instanceof List<?> list ? list : List.of()) {
            Map<?, ?> item = element instanceof Map<?, ?> map ? map : Map.of();
            Optional<Long> id = NodeInput.positiveId(item.get("recipe_id"));
            Optional<Long> qty = NodeInput.integer(item.get("qty"));
            if (id.isEmpty()) {
                continue;
            }
            if (qty.isEmpty() || qty.get() < 1 || qty.get() > MAX_QTY) {
                throw new InvalidInputException("A quantidade de cada produto vai de 1 a " + MAX_QTY + ".");
            }
            merged.merge(id.get(), qty.get().intValue(), (a, b) -> Math.min(MAX_QTY, a + b));
        }
        List<Item> items = new ArrayList<>();
        merged.forEach((id, qty) -> items.add(new Item(id, qty)));
        return items;
    }

    /**
     * Ids dos produtos.
     *
     * @return os ids
     */
    public List<Long> recipeIds() {
        return items.stream().map(Item::recipeId).toList();
    }

    /**
     * Um produto no combo.
     *
     * @param recipeId produto
     * @param qty      quantidade
     */
    public record Item(long recipeId, int qty) {
    }
}
