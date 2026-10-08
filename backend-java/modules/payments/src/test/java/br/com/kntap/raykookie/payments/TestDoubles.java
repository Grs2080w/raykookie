package br.com.kntap.raykookie.payments;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.payments.api.CardAvailability;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Devoluções de mentira (o módulo de verdade fica acima de pagamentos), o cartão ligado e um registro dos avisos.
 */
@Component
@ConditionalOnProperty(name = "test.doubles", havingValue = "true", matchIfMissing = true)
public class TestDoubles implements RefundRequester, CardAvailability {

    /** Pedidos com devolução de pagamento tardio pedida. */
    final List<Long> late = new CopyOnWriteArrayList<>();

    /** Conciliações pedidas ({@code pedido:pagamento}). */
    final List<String> reconciled = new CopyOnWriteArrayList<>();

    /** Avisos publicados. */
    final List<Object> notices = new CopyOnWriteArrayList<>();

    /** Se o cartão está ligado. */
    volatile boolean cardEnabled = true;

    /** {@inheritDoc} */
    @Override
    public boolean refundLatePayment(long orderId) {
        late.add(orderId);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void reconcileRefunds(long orderId, String paymentId) {
        reconciled.add(orderId + ":" + paymentId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean enabled() {
        return cardEnabled;
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

    /**
     * Zera os registros.
     */
    void reset() {
        late.clear();
        reconciled.clear();
        notices.clear();
        cardEnabled = true;
    }
}
