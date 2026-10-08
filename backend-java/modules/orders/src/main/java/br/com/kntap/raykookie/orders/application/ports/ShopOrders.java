package br.com.kntap.raykookie.orders.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pedidos da loja (tabela {@code shop_orders}) e as linhas que eles criam na tela Pedidos.
 */
public interface ShopOrders {

    /**
     * Diz se o código já está em uso.
     *
     * @param code código
     * @return verdadeiro se estiver
     */
    boolean codeTaken(int code);

    /**
     * Grava o pedido.
     *
     * @param order dados do pedido novo
     * @return o id
     */
    long insert(NewShopOrder order);

    /**
     * Grava uma linha na tela Pedidos.
     *
     * @param line dados da linha
     */
    void insertLine(NewLine line);

    /**
     * Guarda telefone e endereço para preencher o próximo pedido.
     *
     * @param customer cliente
     * @param form     pedido
     */
    void rememberContact(CustomerId customer, ShopOrderForm form);

    /**
     * Pedidos do cliente pagos online que ainda não foram pagos nem cancelados (G11).
     *
     * @param customer cliente
     * @return quantos
     */
    int openUnpaidOnline(CustomerId customer);

    /**
     * Pedidos do cliente (os 100 mais novos) como {@code getCustomerOrders} lê.
     *
     * @param customer cliente
     * @param only     só este pedido (nulo = todos)
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> customerRows(CustomerId customer, Long only);

    /**
     * Devoluções (menos as desistidas) destes pedidos, mais novas primeiro.
     *
     * @param orderIds pedidos
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> refundRows(List<Long> orderIds);

    /**
     * O pedido, se for do cliente, para o cancelamento.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @return os dados do cancelamento
     */
    Optional<CancelTarget> cancelTarget(long orderId, CustomerId customer);

    /**
     * Trava os itens do pedido e lê a situação de cada um ({@code FOR UPDATE}).
     *
     * @param orderId pedido
     * @return as situações
     */
    List<String> lockStatuses(long orderId);

    /**
     * Cancela os itens ainda novos e marca o pedido como cancelado pelo cliente.
     *
     * @param orderId pedido
     * @param label   rótulo do motivo
     * @param code    código do motivo
     * @param note    texto do cliente
     */
    void cancelByCustomer(long orderId, String label, String code, String note);

    /**
     * Mantém {@code delivered_at} em dia.
     *
     * @param orderId pedido
     */
    void syncDelivered(long orderId);

    /**
     * Situação atual e dados para o aviso ao cliente.
     *
     * @param orderId pedido
     * @return os dados
     */
    Optional<StatusInfo> statusInfo(long orderId);

    /**
     * Pedido novo.
     *
     * @param owner    dona
     * @param customer cliente
     * @param form     dados conferidos
     * @param code     número que o cliente vê
     * @param pickup   endereço de retirada da época (vazio = nenhum)
     */
    record NewShopOrder(OwnerId owner, CustomerId customer, ShopOrderForm form, int code, String pickup) {
    }

    /**
     * Linha nova na tela Pedidos.
     *
     * @param owner       dona
     * @param number      número do pedido
     * @param shopOrderId pedido da loja
     * @param customer    nome do cliente
     * @param line        item
     * @param delivery    data de entrega
     * @param notes       observações
     * @param address     endereço ou retirada
     */
    record NewLine(OwnerId owner, int number, long shopOrderId, String customer, ShopOrderForm.Line line,
            LocalDate delivery, String notes, String address) {
    }

    /**
     * Pedido a cancelar.
     *
     * @param id            pedido
     * @param code          número que o cliente vê
     * @param owner         dona
     * @param contactName   nome
     * @param total         total
     * @param paymentMethod forma de pagamento
     * @param paid          se foi pago
     * @param mpPaymentId   Pix mais recente (nulo = nenhum)
     */
    record CancelTarget(long id, int code, OwnerId owner, String contactName, Money total, String paymentMethod,
            boolean paid, String mpPaymentId) {
    }

    /**
     * Dados do aviso de mudança de situação.
     *
     * @param statuses    situação de cada item
     * @param customer    cliente
     * @param code        número
     * @param total       total
     * @param fulfillment retirada ou entrega
     * @param timeWindow  período
     * @param paid        se foi pago
     */
    record StatusInfo(List<String> statuses, CustomerId customer, int code, Money total, String fulfillment,
            String timeWindow, boolean paid) {
    }
}
