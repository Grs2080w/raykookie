package br.com.kntap.raykookie.refunds;

import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.payments.api.CardAvailability;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Registro dos avisos publicados (e o cartão ligado, que o app informa em produção).
 */
@Component
public class NoticeLog implements CardAvailability {

    /** Avisos. */
    final List<Object> notices = new CopyOnWriteArrayList<>();

    /** {@inheritDoc} */
    @Override
    public boolean enabled() {
        return true;
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
