package br.com.kntap.raykookie.catalog.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.List;
import java.util.Map;

/**
 * Uma versão de receita do produto (Receita 1, Receita 2…): nome, rendimento, preparo e ingredientes. Margem, preço
 * fixo, categoria, foto e peso ficam no produto.
 *
 * @param name       nome (até 40)
 * @param yieldUnits unidades que rende (> 0)
 * @param prepHours  horas de preparo
 * @param items      ingredientes usados
 */
public record VariantForm(String name, double yieldUnits, double prepHours, List<RecipeForm.Item> items) {

    /**
     * Lê e valida o corpo.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static VariantForm from(Map<String, Object> body) {

        // ── 1. Nome e rendimento.
        String name = NodeInput.cut(NodeInput.text(body.get("name")), 40);
        if (name.isEmpty()) {
            throw new InvalidInputException("Dê um nome para esta receita.");
        }
        double yieldUnits = NodeInput.number(body.get("yield_units"), 1);
        if (yieldUnits <= 0) {
            throw new InvalidInputException("O rendimento precisa ser maior que zero.");
        }

        // ── 2. Preparo e ingredientes.
        double prepHours = Math.max(0, NodeInput.number(body.get("prep_hours")));
        return new VariantForm(name, yieldUnits, prepHours, RecipeForm.items(body.get("items")));
    }

    /**
     * Ids dos ingredientes usados, sem repetição.
     *
     * @return os ids
     */
    public List<Long> ingredientIds() {
        return items.stream().map(RecipeForm.Item::ingredientId).distinct().toList();
    }
}
