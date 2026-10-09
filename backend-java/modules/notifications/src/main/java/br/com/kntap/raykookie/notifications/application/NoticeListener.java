package br.com.kntap.raykookie.notifications.application;

import br.com.kntap.raykookie.customers.api.events.PrivacyRequestCreated;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.notifications.domain.Messages;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escuta os avisos publicados pelos módulos e manda o push <b>depois do commit</b>, fora da thread da
 * requisição: se a transação desfizer, nenhum aviso sai (fora de transação, sai na hora).
 */
@Component
public class NoticeListener {

    /** Envio. */
    private final PushService push;

    /**
     * Cria o ouvinte.
     *
     * @param push envio
     */
    public NoticeListener(PushService push) {
        this.push = push;
    }

    /**
     * Aviso para a dona.
     *
     * @param notice o aviso
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onOwnerNotice(OwnerNotice notice) {
        switch (notice) {
            case OwnerNotice.NewOrder o -> push.toOwner(o.owner(), d -> Messages.newOrder(d.token(), d.sound(), o),
                    "pedido " + o.orderId());
            case OwnerNotice.Paid o -> push.toOwner(o.owner(), d -> Messages.paid(d.token(), o),
                    "pagamento " + o.orderId());
            case OwnerNotice.Cancelled o -> push.toOwner(o.owner(), d -> Messages.cancelled(d.token(), o),
                    "cancelamento " + o.orderId());
            case OwnerNotice.PrivacyRequest o -> push.toOwner(o.owner(), d -> Messages.privacy(d.token(), o),
                    "privacidade " + o.requestId());
            case OwnerNotice.Refund o -> push.toOwner(o.owner(), d -> Messages.refund(d.token(), o),
                    "devolução " + o.refundId());
            case OwnerNotice.PriceAlert o -> push.toOwner(o.owner(), d -> Messages.priceAlert(d.token(), o),
                    "preço " + o.recipeId());
        }
    }

    /**
     * Aviso para um cliente.
     *
     * @param notice o aviso
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onCustomerNotice(CustomerNotice notice) {
        push.toCustomer(notice.order().customer(), token -> Messages.customer(token, notice),
                notice.getClass().getSimpleName() + " " + notice.order().id());
    }

    /**
     * Solicitação de privacidade (evento do módulo {@code customers}).
     *
     * @param event a solicitação
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onPrivacyRequest(PrivacyRequestCreated event) {
        onOwnerNotice(new OwnerNotice.PrivacyRequest(event.owner(), event.requestId(), event.type()));
    }
}
