package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.rules.RefundRules;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * O cliente pede devolução de um pedido pago ({@code requestRefund}) ou desiste enquanto ela não foi analisada
 * ({@code withdrawRefund}).
 */
@Service
public class CustomerRefundUseCases {

    /** JSON dos itens marcados. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Pedidos. */
    private final RefundOrders orders;

    /** Devoluções. */
    private final Refunds refunds;

    /** Histórico. */
    private final OrdersApi history;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transações. */
    private final TransactionTemplate transactions;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param orders       pedidos
     * @param refunds      devoluções
     * @param history      histórico
     * @param events       avisos
     * @param transactions transações
     * @param clock        relógio
     */
    public CustomerRefundUseCases(RefundOrders orders, Refunds refunds, OrdersApi history,
            ApplicationEventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.history = history;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Pede a devolução.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @param form     pedido já conferido (motivo, descrição, fotos)
     * @return {@code ok} e o {@code id} da solicitação
     */
    public Map<String, Object> request(long orderId, CustomerId customer, RefundRequestForm form) {
        Requested r = transactions.execute(status -> {

            // ── 1. Pedido do cliente que aceita solicitação; fotos quando exigidas.
            RefundOrders.OrderState o = orders.load(orderId, true).filter(x -> x.customer().equals(customer))
                    .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));
            RefundRules.Eligibility e = RefundRules.eligibility(new RefundRules.Input(o.paid(), o.paidAt(), o.total(),
                    o.refundedTotal().plus(o.committed()), o.statuses(), o.deliveredAt(), o.liveRequest(),
                    clock.instant()));
            if (!"solicitacao".equals(e.mode())) {
                throw new ConflictException(e.whyNot().isEmpty()
                        ? "Este pedido não aceita pedido de devolução. Fale com a gente." : e.whyNot());
            }
            if (e.photosRequired() && "sim".equals(form.reason().photos()) && form.photos().isEmpty()) {
                throw new InvalidInputException("Envie pelo menos uma foto para mostrar o problema.");
            }

            // ── 2. Itens marcados e o valor (até o que ainda pode voltar).
            RefundRequestForm.Picked picked = form.pick(o.items().stream()
                    .collect(Collectors.toMap(RefundRequestForm.OrderedItem::id, Function.identity(), (a, b) -> a)));
            Money requested = e.maxAmount().isLessThan(picked.requested()) ? e.maxAmount() : picked.requested();

            // ── 3. Grava a solicitação, as fotos e o histórico.
            return new Requested(save(o, form, picked, requested), o, requested);
        });

        // ── 4. Avisa a dona.
        events.publishEvent(new OwnerNotice.Refund(r.order().owner(), "solicitada", r.id(), r.order().code(),
                r.order().contactName(), r.requested(), form.reason().priority(), form.reason().label(),
                form.photos().size(), false));
        return Map.of("ok", true, "id", r.id());
    }

    /**
     * Grava a solicitação, as fotos e o histórico.
     *
     * @param o         pedido
     * @param form      pedido de devolução
     * @param picked    itens marcados
     * @param requested valor pedido
     * @return o id
     */
    private long save(RefundOrders.OrderState o, RefundRequestForm form, RefundRequestForm.Picked picked,
            Money requested) {

        // ── 1. A solicitação e as fotos.
        long id = refunds.insert(new Refunds.NewRefund(o.id(), o.owner(), o.customer(), "solicitado", "solicitado",
                form.reason().id(), form.description(), JSON.writeValueAsString(picked.items().stream()
                        .map(i -> Map.of("id", i.id(), "name", i.name(), "qty", i.qty())).toList()),
                requested, null, "", form.reason().priority()));
        refunds.insertPhotos(id, form.photos());

        // ── 2. O histórico do pedido.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", id);
        data.put("reason", form.reason().id());
        data.put("requested", NodeRows.number(requested.toDecimal()));
        data.put("photos", form.photos().size());
        history.logEvent(o.id(), "cliente", "devolucao_solicitada", data);
        return id;
    }

    /**
     * Desiste da solicitação ainda não analisada.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @return {@code {"ok":true}}
     */
    public Map<String, Object> withdraw(long orderId, CustomerId customer) {
        Refunds.Withdrawn w = refunds.withdraw(orderId, customer)
                .orElseThrow(() -> new ConflictException("Não há solicitação em análise para cancelar."));
        history.logEvent(orderId, "cliente", "devolucao_desistiu", Map.of("refund_id", w.id()));
        events.publishEvent(new OwnerNotice.Refund(w.owner(), "desistiu", w.id(), w.code(), w.contactName(), null,
                false, null, 0, false));
        return Map.of("ok", true);
    }

    /**
     * Solicitação gravada.
     *
     * @param id        devolução
     * @param order     pedido
     * @param requested valor pedido
     */
    private record Requested(long id, RefundOrders.OrderState order, Money requested) {
    }
}
