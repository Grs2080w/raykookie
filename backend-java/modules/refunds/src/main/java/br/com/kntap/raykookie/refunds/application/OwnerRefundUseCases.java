package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.refunds.application.ports.RefundOrders;
import br.com.kntap.raykookie.refunds.application.ports.Refunds;
import br.com.kntap.raykookie.refunds.domain.RefundAmounts;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O que a dona faz com as devoluções: devolver um valor sem cancelar ({@code ownerRefund}), decidir uma solicitação
 * ({@code decideRefund}) e refazer uma que falhou ({@code retryRefund}).
 */
@Service
public class OwnerRefundUseCases {

    /** Pedidos. */
    private final RefundOrders orders;

    /** Devoluções. */
    private final Refunds refunds;

    /** Passos comuns. */
    private final RefundRecorder recorder;

    /** Envio. */
    private final RefundSender sender;

    /** Histórico. */
    private final OrdersApi history;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transações. */
    private final TransactionTemplate transactions;

    /**
     * Cria os casos de uso.
     *
     * @param orders       pedidos
     * @param refunds      devoluções
     * @param recorder     passos comuns
     * @param sender       envio
     * @param history      histórico
     * @param events       avisos
     * @param transactions transações
     */
    public OwnerRefundUseCases(RefundOrders orders, Refunds refunds, RefundRecorder recorder, RefundSender sender,
            OrdersApi history, ApplicationEventPublisher events, TransactionTemplate transactions) {

        // ── 1. Dados e passos.
        this.orders = orders;
        this.refunds = refunds;
        this.recorder = recorder;
        this.sender = sender;

        // ── 2. Integrações.
        this.history = history;
        this.events = events;
        this.transactions = transactions;
    }

    /**
     * Devolve um valor sem cancelar o pedido (ex.: faltou um item).
     *
     * @param orderId pedido
     * @param owner   dona
     * @param amount  valor digitado (pt-BR)
     * @param note    motivo
     * @return {@code ok}, {@code refundId} e {@code refunded}
     */
    public Map<String, Object> ownerRefund(long orderId, OwnerId owner, Object amount, Object note) {

        // ── 1. Valor e motivo.
        Money value = NodeInput.localeMoney(NodeInput.text(amount)).orElse(Money.ZERO);
        String n = NodeInput.cut(NodeInput.text(note), 300);
        if (value.cents() <= 0) {
            throw new InvalidInputException("Informe o valor da devolução.");
        }
        if (n.length() < 3) {
            throw new InvalidInputException("Conte o motivo da devolução.");
        }

        // ── 2. Na transação: confere o restante, registra e encerra se devolveu tudo.
        long refundId = transactions.execute(status -> {
            RefundOrders.OrderState o = orders.load(orderId, true).filter(x -> x.owner().equals(owner))
                    .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));
            checkRefundable(o, value);
            long id = refunds.insert(new Refunds.NewRefund(o.id(), o.owner(), o.customer(), "loja", "processando",
                    "outro", n, "[]", value, value, n, false));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("refund_id", id);
            data.put("amount", NodeRows.number(value.toDecimal()));
            data.put("note", n);
            history.logEvent(o.id(), "loja", "devolucao_aprovada", data);
            if (!o.manual()) {
                recorder.closeIfFullyRefunded(o.id());
            }
            return id;
        });

        // ── 3. Manda ao Mercado Pago.
        sender.send(refundId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("refundId", refundId);
        result.put("refunded", NodeRows.number(value.toDecimal()));
        return result;
    }

    /**
     * Pode devolver este valor?
     *
     * @param o     pedido
     * @param value valor
     */
    private static void checkRefundable(RefundOrders.OrderState o, Money value) {
        if (!o.paid()) {
            throw new ConflictException("Este pedido ainda não foi pago.");
        }
        if (o.pendingRequest()) {
            throw new ConflictException(CancelRefundUseCases.PENDING);
        }
        if (o.left().cents() <= 0) {
            throw new ConflictException("Todo o valor deste pedido já foi devolvido.");
        }
        if (o.left().isLessThan(value)) {
            throw new InvalidInputException(RefundAmounts.tooMuch(o.left()));
        }
    }

    /**
     * Aprova (total ou parcial) ou nega uma solicitação.
     *
     * @param refundId devolução
     * @param owner    dona
     * @param approve  aprovar
     * @param amount   valor aprovado (vazio = o pedido)
     * @param code     motivo da negativa
     * @param note     observação ao cliente
     * @return {@code ok} e {@code status}
     */
    public Map<String, Object> decide(long refundId, OwnerId owner, boolean approve, Object amount, Object code,
            Object note) {

        // ── 1. Decide na transação.
        String n = NodeInput.cut(NodeInput.text(note), 500);
        Decision d = transactions.execute(status -> {
            Refunds.RefundRow r = refunds.lock(refundId, owner)
                    .orElseThrow(() -> new NotFoundException("Solicitação não encontrada."));
            if (!"solicitado".equals(r.status())) {
                throw new ConflictException("Esta solicitação já foi decidida.");
            }
            return approve ? approve(r, amount, n) : deny(r, code, n);
        });

        // ── 2. Avisa o cliente e, aprovada, manda.
        events.publishEvent(d.notice());
        if (d.approved()) {
            sender.send(refundId);
        }
        return Map.of("ok", true, "status", d.approved() ? "aprovado" : "negado");
    }

    /**
     * Aprova.
     *
     * @param r      solicitação
     * @param amount valor (vazio = o pedido)
     * @param note   observação
     * @return a decisão
     */
    private Decision approve(Refunds.RefundRow r, Object amount, String note) {

        // ── 1. Valor: não passa do restante; parcial exige explicação.
        RefundOrders.OrderState o = orders.load(r.orderId(), true).orElseThrow();
        Money value = amount == null || "".equals(amount) ? r.requested()
                : NodeInput.localeMoney(NodeInput.text(amount)).orElse(Money.ZERO);
        if (value.cents() <= 0) {
            throw new InvalidInputException("Informe o valor a devolver.");
        }
        if (o.left().isLessThan(value)) {
            throw new InvalidInputException(RefundAmounts.tooMuch(o.left()));
        }
        boolean partial = value.isLessThan(r.requested());
        if (partial && note.length() < 3) {
            throw new InvalidInputException("Para devolver menos que o pedido, explique o motivo ao cliente.");
        }

        // ── 2. Grava, registra e encerra se devolveu tudo.
        refunds.approve(r.id(), value, note);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", r.id());
        data.put("amount", NodeRows.number(value.toDecimal()));
        data.put("partial", partial);
        data.put("note", note);
        history.logEvent(r.orderId(), "loja", "devolucao_aprovada", data);
        recorder.closeIfFullyRefunded(r.orderId());
        return new Decision(true, new CustomerNotice.Refund(o.ref(), "aprovada", value, r.requested(), null));
    }

    /**
     * Nega com um motivo da lista.
     *
     * @param r    solicitação
     * @param code motivo
     * @param note observação
     * @return a decisão
     */
    private Decision deny(Refunds.RefundRow r, Object code, String note) {

        // ── 1. Motivo da lista; "outro" exige explicação.
        if (!Reasons.contains(Reasons.DENY, code)) {
            throw new InvalidInputException("Escolha o motivo da negativa.");
        }
        if ("outro".equals(code) && note.length() < 3) {
            throw new InvalidInputException("Explique o motivo ao cliente.");
        }

        // ── 2. Grava, registra e prepara o aviso.
        refunds.deny(r.id(), String.valueOf(code), note);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refund_id", r.id());
        data.put("code", code);
        data.put("note", note);
        history.logEvent(r.orderId(), "loja", "devolucao_negada", data);
        RefundOrders.OrderState o = orders.load(r.orderId(), false).orElseThrow();
        return new Decision(false, new CustomerNotice.Refund(o.ref(), "negada", null, null,
                Reasons.label(String.valueOf(code))));
    }

    /**
     * Refaz uma devolução que falhou (botão da dona).
     *
     * @param refundId devolução
     * @param owner    dona
     * @return {@code ok} e a nova situação
     */
    public Map<String, Object> retry(long refundId, OwnerId owner) {
        String status = refunds.status(refundId, owner)
                .orElseThrow(() -> new NotFoundException("Devolução não encontrada."));
        if (!"falhou".equals(status) && !"processando".equals(status)) {
            throw new ConflictException("Esta devolução não precisa ser refeita.");
        }
        return Map.of("ok", true, "status", sender.send(refundId));
    }

    /**
     * Decisão tomada.
     *
     * @param approved se aprovou
     * @param notice   aviso ao cliente
     */
    private record Decision(boolean approved, CustomerNotice.Refund notice) {
    }
}
