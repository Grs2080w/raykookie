package br.com.kntap.raykookie.orders;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import br.com.kntap.raykookie.orders.api.ports.PaidOrderCanceller;
import br.com.kntap.raykookie.orders.api.ports.PendingChargeCanceller;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pagamentos e devoluções de mentira (os módulos de verdade ficam acima de pedidos) e um registro dos avisos.
 */
@Component
@ConditionalOnProperty(name = "test.fake-ports", havingValue = "true", matchIfMissing = true)
public class FakePorts implements ChargeStarter, PendingChargeCanceller, PaidOrderCanceller {

    /** Pedidos que pediram Pix. */
    final List<NewOrder> pix = new CopyOnWriteArrayList<>();

    /** Pedidos cancelados pela devolução. */
    final List<Long> refunded = new CopyOnWriteArrayList<>();

    /** Avisos publicados. */
    final List<Object> notices = new CopyOnWriteArrayList<>();

    /** Resposta da conferência do Pix pendente. */
    volatile boolean settled;

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> startPix(NewOrder order) {
        pix.add(order);
        return Map.of("code", "pix-" + order.id(), "qr_base64", "", "expires_in", 3600);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> startCard(NewOrder order, Card card, String clientIp) {
        return Map.of("approved", true);
    }

    /** {@inheritDoc} */
    @Override
    public boolean settleBeforeCancel(long orderId, String mpPaymentId) {
        return settled;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> cancelPaidByCustomer(long orderId, CustomerId customer, String code, String note) {
        refunded.add(orderId);
        return Map.of("ok", true, "state", "cancelled", "refunded", 9.5);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> cancelByOwner(long orderId, OwnerId owner, Object code, Object note) {
        refunded.add(orderId);
        return Map.of("ok", true, "refundId", 1, "refunded", 0);
    }

    /**
     * Guarda os avisos à dona.
     *
     * @param notice aviso
     */
    @EventListener
    public void owner(OwnerNotice notice) {
        notices.add(notice);
    }

    /**
     * Guarda os avisos ao cliente.
     *
     * @param notice aviso
     */
    @EventListener
    public void customer(CustomerNotice notice) {
        notices.add(notice);
    }
}
