package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.orders.api.rules.OrderStatus;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Pedido registrado à mão na tela Pedidos ({@code parseOrder} de {@code backend/lib/order-input.ts}).
 *
 * @param orderDate    data do pedido (padrão: hoje)
 * @param customer     cliente (até 80)
 * @param recipeId     receita (nula = nenhuma)
 * @param product      produto (até 100)
 * @param qty          quantidade
 * @param unitPrice    preço unitário
 * @param deliveryDate data de entrega (nula = nenhuma)
 * @param status       situação
 * @param payment      pagamento
 * @param notes        observações (até 300)
 */
public record ManualOrderForm(LocalDate orderDate, String customer, Long recipeId, String product, BigDecimal qty,
        BigDecimal unitPrice, LocalDate deliveryDate, String status, String payment, String notes) {

    /**
     * Lê e valida o corpo, na ordem do Node.
     *
     * @param body  corpo JSON
     * @param today hoje no fuso da loja
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static ManualOrderForm from(Map<String, Object> body, LocalDate today) {

        // ── 1. Cliente, produto e quantidade.
        String customer = NodeInput.text(body.get("customer"));
        String product = NodeInput.text(body.get("product"));
        if (customer.isEmpty()) {
            throw new InvalidInputException("Informe o nome do cliente.");
        }
        if (product.isEmpty()) {
            throw new InvalidInputException("Informe o produto.");
        }
        BigDecimal qty = NodeInput.decimal(body.get("qty"), 1);
        if (qty.signum() <= 0) {
            throw new InvalidInputException("A quantidade precisa ser maior que zero.");
        }

        // ── 2. Datas: preenchidas precisam existir.
        if (invalidDate(body.get("order_date")) || invalidDate(body.get("delivery_date"))) {
            throw new InvalidInputException("Data inválida.");
        }

        // ── 3. O resto, com os padrões do Node.
        String status = NodeInput.text(body.get("status"));
        String payment = NodeInput.text(body.get("payment"));
        return new ManualOrderForm(IsoDates.parse(body.get("order_date")).orElse(today), NodeInput.cut(customer, 80),
                NodeInput.optionalId(body.get("recipe_id")), NodeInput.cut(product, 100), qty,
                NodeInput.decimal(body.get("unit_price"), 0), IsoDates.parse(body.get("delivery_date")).orElse(null),
                OrderStatus.ALL.contains(status) ? status : "novo",
                OrderStatus.PAYMENTS.contains(payment) ? payment : "pendente",
                NodeInput.cut(NodeInput.text(body.get("notes")), 300));
    }

    /**
     * Data preenchida que não existe.
     *
     * @param value valor
     * @return verdadeiro se for inválida
     */
    private static boolean invalidDate(Object value) {
        return !NodeInput.text(value).isEmpty() && IsoDates.parse(value).isEmpty();
    }
}
