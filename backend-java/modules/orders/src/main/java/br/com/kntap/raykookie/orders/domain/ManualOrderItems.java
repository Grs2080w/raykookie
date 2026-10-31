package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pedido registrado à mão com vários itens: o corpo traz os campos do pedido (cliente, datas, situação, pagamento,
 * observações) e {@code items}, uma lista de {@code {id?, recipe_id, product, qty, unit_price}}. Cada item vira um
 * {@link ManualOrderForm} com os campos do pedido, então a validação é a mesma do pedido de um item só.
 *
 * @param id   linha já existente do item (nulo = item novo)
 * @param form dados da linha
 */
public record ManualOrderItems(Long id, ManualOrderForm form) {

    /** Itens por pedido. */
    public static final int MAX = 30;

    /**
     * Lê os itens do corpo.
     *
     * @param body  corpo JSON
     * @param today hoje no fuso da loja
     * @return os itens na ordem enviada; lista vazia se o corpo não trouxer {@code items}
     * @throws InvalidInputException se os itens vierem malformados ou passarem do limite
     */
    public static List<ManualOrderItems> from(Map<String, Object> body, LocalDate today) {

        // ── 1. Sem lista, o corpo é de um item só (formato antigo).
        if (!(body.get("items") instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX) {
            throw new InvalidInputException("Um pedido aceita até " + MAX + " itens.");
        }

        // ── 2. Cada item herda os campos do pedido e traz os seus.
        List<ManualOrderItems> items = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> item)) {
                throw new InvalidInputException("Item inválido.");
            }
            Map<String, Object> line = new HashMap<>(body);
            line.remove("items");
            for (String key : List.of("recipe_id", "product", "qty", "unit_price")) {
                line.put(key, item.get(key));
            }
            items.add(new ManualOrderItems(NodeInput.positiveId(item.get("id")).orElse(null),
                    ManualOrderForm.from(line, today)));
        }
        return items;
    }
}
