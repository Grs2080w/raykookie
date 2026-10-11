package br.com.kntap.raykookie.catalog.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Formulário de receita, com as regras e as mensagens de {@code backend/lib/recipe-save.ts}.
 *
 * @param categoryId  categoria (nula = nenhuma)
 * @param name        nome (até 80)
 * @param yieldUnits  unidades que rende (> 0)
 * @param marginPct   margem (%)
 * @param prepHours   horas de preparo
 * @param notes       observações (até 500)
 * @param unitWeightG gramas de cada unidade (0 = não informado)
 * @param salePrice   preço fixado (nulo = o sugerido)
 * @param items       ingredientes usados
 */
public record RecipeForm(Long categoryId, String name, double yieldUnits, double marginPct, double prepHours,
        String notes, double unitWeightG, Double salePrice, List<Item> items) {

    /** Teto dos campos numéricos grandes (como no Node). */
    private static final double MAX_VALUE = 99_999_999;

    /**
     * Lê e valida o corpo, na mesma ordem do Node.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static RecipeForm from(Map<String, Object> body) {

        // ── 1. Nome e rendimento.
        String name = NodeInput.text(body.get("name"));
        if (name.isEmpty()) {
            throw new InvalidInputException("Dê um nome para a receita.");
        }
        double yieldUnits = NodeInput.number(body.get("yield_units"), 1);
        if (yieldUnits <= 0) {
            throw new InvalidInputException("O rendimento precisa ser maior que zero.");
        }

        // ── 2. Itens, depois os outros campos na ordem do Node.
        List<Item> items = items(body.get("items"));
        Long categoryId = NodeInput.optionalId(body.get("category_id"));
        double marginPct = NodeInput.number(body.get("margin_pct"), 100);
        double prepHours = NodeInput.number(body.get("prep_hours"));
        String notes = NodeInput.cut(NodeInput.text(body.get("notes")), 500);
        double unitWeightG = Math.min(NodeInput.number(body.get("unit_weight_g")), MAX_VALUE);
        return new RecipeForm(categoryId, NodeInput.cut(name, 80), yieldUnits, marginPct, prepHours, notes,
                unitWeightG, salePrice(body.get("sale_price")), items);
    }

    /**
     * Itens da receita: cada um com id e quantidade; os sem id válido ficam de fora.
     *
     * @param raw valor de {@code items}
     * @return os itens
     */
    public static List<Item> items(Object raw) {
        List<Item> items = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return items;
        }
        for (Object element : list) {
            Map<?, ?> item = element instanceof Map<?, ?> map ? map : Map.of();
            Optional<Long> id = NodeInput.positiveId(item.get("ingredient_id"));
            double qty = NodeInput.number(item.get("qty"));
            id.ifPresent(ingredient -> items.add(new Item(ingredient, qty)));
        }
        return items;
    }

    /**
     * Preço fixado: vazio, nulo ou zero = nenhum.
     *
     * @param raw valor de {@code sale_price}
     * @return o preço, ou nulo
     */
    private static Double salePrice(Object raw) {
        if (raw == null || "".equals(raw)) {
            return null;
        }
        double price = Math.min(NodeInput.number(raw), MAX_VALUE);
        return price == 0 ? null : price;
    }

    /**
     * Ids dos ingredientes usados, sem repetição.
     *
     * @return os ids
     */
    public List<Long> ingredientIds() {
        return items.stream().map(Item::ingredientId).distinct().toList();
    }

    /**
     * Um ingrediente na receita.
     *
     * @param ingredientId ingrediente
     * @param qty          quantidade (g, ml ou un)
     */
    public record Item(long ingredientId, double qty) {
    }
}
