package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.orders.application.ports.ManualOrders;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.orders.domain.OrderStatus;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Depois de mudar a situação de um item: atualiza a entrega e, se a etapa geral do pedido da loja mudou, avisa o
 * cliente (porte de {@code backend/lib/order-sync.ts}).
 */
@Component
public class StatusChanges {

    /** Pedidos da loja. */
    private final ShopOrders shopOrders;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /**
     * Cria o componente.
     *
     * @param shopOrders pedidos da loja
     * @param events     avisos
     */
    public StatusChanges(ShopOrders shopOrders, ApplicationEventPublisher events) {
        this.shopOrders = shopOrders;
        this.events = events;
    }

    /**
     * Compara a etapa geral de antes com a de agora.
     *
     * @param before situação antes da mudança (vazio = linha de pedido à mão)
     */
    public void after(Optional<ManualOrders.Snapshot> before) {

        // ── 1. Só pedidos da loja: a entrega conta para o prazo de devolução.
        if (before.isEmpty()) {
            return;
        }
        long orderId = before.get().shopOrderId();
        shopOrders.syncDelivered(orderId);

        // ── 2. Mudou a etapa geral: o cliente fica sabendo.
        Optional<ShopOrders.StatusInfo> info = shopOrders.statusInfo(orderId);
        String was = OrderStatus.overall(before.get().statuses());
        info.filter(now -> !OrderStatus.overall(now.statuses()).equals(was)).ifPresent(now -> events.publishEvent(
                new CustomerNotice.Status(new CustomerNotice.OrderRef(orderId, now.code(), now.customer(), now.total(),
                        now.fulfillment(), now.timeWindow(), now.paid()), OrderStatus.overall(now.statuses()))));
    }
}
