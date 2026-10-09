package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.payments.api.ports.RefundRequester;
import br.com.kntap.raykookie.payments.application.ports.PayLinks;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pagamento de link aplicado ({@code markLinkPaid}, {@code promoteLink}, {@code handleLinkPayment},
 * {@code reconcileLinks}): o pedido registrado à mão pago pelo link vira pedido do app, com cliente convidado.
 */
@Service
public class LinkPayments {

    /** Prefixo do {@code external_reference} dos pagamentos de link. */
    public static final String LINK_REF = "link:";

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(LinkPayments.class);

    /** Links. */
    private final PayLinks links;

    /** Pedidos (código, histórico, entrega). */
    private final OrdersApi orders;

    /** Devoluções. */
    private final ObjectProvider<RefundRequester> refunds;

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transações. */
    private final TransactionTemplate transactions;

    /** Alertas. */
    private final OpsAlert alert;

    /**
     * Cria o serviço.
     *
     * @param links        links
     * @param orders       pedidos
     * @param refunds      devoluções
     * @param gateway      gateway
     * @param events       avisos
     * @param transactions transações
     * @param alert        alertas
     */
    public LinkPayments(PayLinks links, OrdersApi orders, ObjectProvider<RefundRequester> refunds,
            PaymentGateway gateway, ApplicationEventPublisher events, TransactionTemplate transactions,
            OpsAlert alert) {

        // ── 1. Links, pedidos e devoluções.
        this.links = links;
        this.orders = orders;
        this.refunds = refunds;

        // ── 2. Gateway, avisos e transações.
        this.gateway = gateway;
        this.events = events;
        this.transactions = transactions;
        this.alert = alert;
    }

    /**
     * Marca o link e a linha como pagos e promove a pedido do app. Só vale para pagamento gerado para o link e que
     * cobre o valor.
     *
     * @param linkId     link
     * @param paymentId  pagamento
     * @param paidAmount valor pago
     * @return a linha marcada, ou vazio se não se aplica
     */
    public Optional<Long> markPaid(long linkId, String paymentId, Money paidAmount) {
        Done done = transactions.execute(status -> {

            // ── 1. Trava a linha e marca o link (só pagamento do link que cobre o valor).
            Optional<PayLinks.LineState> before = links.lockLine(linkId);
            Optional<Long> orderId = links.markPaid(linkId, paymentId, paidAmount);
            if (orderId.isEmpty()) {
                return null;
            }
            before.filter(b -> "cancelado".equals(b.status()) || "pago".equals(b.payment())).ifPresent(b ->
                    LOG.warn("Pagamento {} recebido para a linha {} cancelada ou já paga", paymentId, b.orderId()));

            // ── 2. Promove na mesma transação (e registra a devolução se a linha estava cancelada).
            return new Done(orderId.get(), promote(linkId, paymentId, false));
        });
        if (done == null) {
            return Optional.empty();
        }
        afterPromotion(done.promoted());
        return Optional.of(done.orderId());
    }

    /**
     * Promove um link antigo já pago que ainda não virou pedido do app.
     *
     * @param linkId link
     * @return verdadeiro se promoveu agora
     */
    public boolean promoteLegacy(long linkId) {
        Promoted promoted = transactions.execute(status -> {
            links.lockLine(linkId);
            return links.lockLegacyPaid(linkId).map(paidMpId -> promote(linkId, paidMpId, true)).orElse(null);
        });
        afterPromotion(promoted);
        return promoted != null;
    }

    /**
     * Pagamento de link avisado pelo webhook (já consultado no gateway).
     *
     * @param payment pagamento
     * @param linkId  link
     * @return {@code ok}, {@code status} e {@code paid}
     */
    public Map<String, Object> handle(GatewayPayment payment, long linkId) {

        // ── 1. Estorno de link já promovido: concilia a devolução.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("status", payment.status());
        if (payment.refundedOrChargedBack()) {
            links.promoted(linkId).filter(p -> p.shopOrderId() != null && payment.id().equals(p.paidMpId()))
                    .ifPresent(p -> reconcile(p.shopOrderId(), payment.id()));
            return result;
        }

        // ── 2. Aprovado: marca.
        if (!payment.approved()) {
            return result;
        }
        boolean paid = markPaid(linkId, payment.id(), payment.amount()).isPresent();
        if (!paid) {
            LOG.warn("Pagamento {} aprovado do link {} não aplicado (já pago, de outro link ou valor menor)",
                    payment.id(), linkId);
        }
        result.put("paid", paid);
        return result;
    }

    /**
     * Rede de segurança do webhook: Pix aprovados de links abertos, links antigos e devoluções que não saíram.
     *
     * @return quantos foram aplicados
     */
    public int reconcile() {

        // ── 1. Links com Pix gerado e não pagos.
        int applied = 0;
        for (PayLinks.OpenLink link : links.open()) {
            applied += applyFirstApproved(link) ? 1 : 0;
        }

        // ── 2. Links antigos e pedidos cancelados sem devolução.
        for (long legacy : links.legacy()) {
            applied += safely(() -> promoteLegacy(legacy), "promover link antigo " + legacy) ? 1 : 0;
        }
        RefundRequester requester = refunds.getIfAvailable();
        for (long orphan : requester == null ? java.util.List.<Long>of() : links.orphans()) {
            applied += safely(() -> transactions.execute(s -> requester.refundLatePayment(orphan)),
                    "devolver pagamento de pedido cancelado " + orphan) ? 1 : 0;
        }
        return applied;
    }

    /**
     * Aplica o primeiro pagamento aprovado de um link.
     *
     * @param link link
     * @return verdadeiro se aplicou
     */
    private boolean applyFirstApproved(PayLinks.OpenLink link) {
        for (String paymentId : link.paymentIds()) {
            boolean done = safely(() -> {
                GatewayPayment p = gateway.getPayment(paymentId);
                return p.approved() && markPaid(link.id(), paymentId, p.amount()).isPresent();
            }, "conciliar o link " + link.id());
            if (done) {
                return true;
            }
        }
        return false;
    }

    /**
     * Roda um passo que não pode derrubar a varredura.
     *
     * @param step passo
     * @param what o que é (para o log)
     * @return o resultado (falso se falhou)
     */
    private static boolean safely(java.util.function.BooleanSupplier step, String what) {
        try {
            return step.getAsBoolean();
        } catch (RuntimeException e) {
            LOG.error("[pagamento por link] falha ao {}", what, e);
            return false;
        }
    }

    /**
     * Concilia as devoluções de um pagamento.
     *
     * @param shopOrderId pedido do app
     * @param paymentId   pagamento
     */
    private void reconcile(long shopOrderId, String paymentId) {
        RefundRequester requester = refunds.getIfAvailable();
        if (requester != null) {
            requester.reconcileRefunds(shopOrderId, paymentId);
        }
    }

    /**
     * Na transação: grava o pedido do app (idempotente: link já promovido não faz nada).
     *
     * @param linkId   link
     * @param paidMpId pagamento aprovado
     * @param legacy   link antigo (pago antes da promoção existir)
     * @return o que foi promovido, ou nulo
     */
    private Promoted promote(long linkId, String paidMpId, boolean legacy) {

        // ── 1. Dados do link travado.
        Optional<PayLinks.PromotionSource> found = links.lockForPromotion(linkId);
        if (found.isEmpty()) {
            return null;
        }
        PayLinks.PromotionSource l = found.get();
        boolean cancelled = "cancelado".equals(l.status());
        String item = String.valueOf(NodeRows.number(l.qty())).replace('.', ',') + " × " + l.product();

        // ── 2. Pedido do app e histórico.
        int code = orders.newPublicCode();
        long shopOrderId = links.promote(new PayLinks.Promotion(linkId, l, code, paidMpId, item, cancelled));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment", paidMpId);
        data.put("amount", NodeRows.number(l.amount().toDecimal()));
        data.put("via", "link");
        orders.logEvent(shopOrderId, "mercadopago", "pago", data);

        // ── 3. Linha cancelada: devolve tudo, registrado nesta transação.
        Promoted promoted = new Promoted(shopOrderId, l.owner(), code, l.customer(), l.amount(), cancelled && !legacy,
                !cancelled && !legacy && "pago".equals(l.payment()), "entregue".equals(l.status()), legacy);
        if (promoted.late()) {
            refundLate(shopOrderId);
        }
        return promoted;
    }

    /**
     * Devolução de pagamento de pedido cancelado.
     *
     * @param shopOrderId pedido do app
     */
    private void refundLate(long shopOrderId) {
        RefundRequester requester = refunds.getIfAvailable();
        if (requester == null) {
            alert.paymentProblem("pagamento de link cancelado sem o módulo de devoluções", Map.of("order",
                    shopOrderId));
            return;
        }
        requester.refundLatePayment(shopOrderId);
    }

    /**
     * Depois do commit: prazo de devolução e aviso à dona.
     *
     * @param p promoção (nula = nada)
     */
    private void afterPromotion(Promoted p) {
        if (p == null) {
            return;
        }
        if (p.delivered()) {
            safely(() -> {
                if (p.legacy()) {
                    links.deliveredAtLegacyPayment(p.shopOrderId());
                } else {
                    orders.syncDelivered(p.shopOrderId());
                }
                return true;
            }, "iniciar o prazo de devolução do pedido " + p.shopOrderId());
        }
        if (!p.legacy()) {
            String name = p.twice() ? p.name() + " (pagamento em dobro: use \"Devolver valor\" para devolver o Pix)"
                    : p.name();
            events.publishEvent(new OwnerNotice.Paid(p.owner(), p.shopOrderId(), p.code(), name, p.amount(), 1));
        }
    }

    /**
     * Linha marcada e a promoção.
     *
     * @param orderId  linha
     * @param promoted promoção (nula se o link já tinha sido promovido)
     */
    private record Done(long orderId, Promoted promoted) {
    }

    /**
     * Pedido promovido.
     *
     * @param shopOrderId pedido do app
     * @param owner       dona
     * @param code        número
     * @param name        cliente
     * @param amount      valor
     * @param late        linha cancelada: devolver
     * @param twice       linha já paga à mão: pagamento em dobro
     * @param delivered   linha entregue: o prazo de devolução começa
     * @param legacy      link antigo
     */
    private record Promoted(long shopOrderId, OwnerId owner, int code, String name, Money amount, boolean late,
            boolean twice, boolean delivered, boolean legacy) {
    }
}
