package br.com.kntap.raykookie.catalog.api;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.Map;
import java.util.Set;

/**
 * Formulário de ingrediente (também usado pela leitura de nota fiscal), com as regras e as mensagens de
 * {@code backend/lib/ingredient-input.ts}.
 *
 * @param categoryId  categoria (nula = nenhuma)
 * @param name        nome (até 80)
 * @param unit        {@code g}, {@code ml} ou {@code un}
 * @param stockQty    estoque, em embalagens
 * @param packageSize tamanho da embalagem
 * @param packageCost custo da embalagem
 * @param minStock    estoque mínimo, em embalagens
 * @param supplier    fornecedor (até 80)
 * @param notes       observações (até 300)
 */
public record IngredientForm(Long categoryId, String name, String unit, double stockQty, double packageSize,
        double packageCost, double minStock, String supplier, String notes) {

    /** Unidades aceitas. */
    private static final Set<String> UNITS = Set.of("g", "ml", "un");

    /**
     * Lê e valida o corpo ({@code parseIngredient}), na mesma ordem do Node.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static IngredientForm from(Map<String, Object> body) {

        // ── 1. Campos obrigatórios (o tamanho é lido antes: número inválido já para aqui).
        String name = NodeInput.text(body.get("name"));
        String unit = NodeInput.text(body.get("unit"));
        double packageSize = NodeInput.number(body.get("package_size"));
        if (name.isEmpty()) {
            throw new InvalidInputException("Informe o nome do ingrediente.");
        }
        if (!UNITS.contains(unit)) {
            throw new InvalidInputException("Escolha a unidade (g, ml ou un).");
        }
        if (packageSize <= 0) {
            throw new InvalidInputException("O tamanho da embalagem precisa ser maior que zero.");
        }

        // ── 2. O resto, na ordem do Node.
        Long categoryId = NodeInput.optionalId(body.get("category_id"));
        double stockQty = NodeInput.number(body.get("stock_qty"));
        double packageCost = NodeInput.number(body.get("package_cost"));
        double minStock = NodeInput.number(body.get("min_stock"));
        return new IngredientForm(categoryId, NodeInput.cut(name, 80), unit, stockQty, packageSize, packageCost,
                minStock, NodeInput.cut(NodeInput.text(body.get("supplier")), 80),
                NodeInput.cut(NodeInput.text(body.get("notes")), 300));
    }

    /**
     * Diz se o corpo é a edição rápida só do estoque ({@code {"stock_qty": …}} e nada mais).
     *
     * @param body corpo JSON
     * @return verdadeiro se só tem {@code stock_qty}
     */
    public static boolean isStockOnly(Map<String, Object> body) {
        return body.size() == 1 && body.containsKey("stock_qty");
    }
}
