package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.payments.application.ports.PayLinks;
import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.application.ports.WebhookDeliveries;
import br.com.kntap.raykookie.payments.domain.CardFailReasons;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tarefas agendadas de pagamentos: conciliação dos Pix e dos links (a cada 6 h, como o Node), tentativas de cartão
 * sem resposta ou em análise (a cada 5 min) e limpeza das entregas de webhook (diária).
 */
@Configuration
public class PaymentJobs {

    /** Tentativa em andamento há mais que isto ficou sem resposta. */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);

    /** Dias que as entregas de webhook ficam guardadas. */
    static final int KEEP_DELIVERIES_DAYS = 30;

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(PaymentJobs.class);

    /**
     * Pix de pedidos da loja que o webhook pode ter perdido ({@code reconcilePending}).
     *
     * @param charges colunas de cobrança
     * @param gateway gateway
     * @param confirm confirmação única
     * @return a tarefa
     */
    @Bean
    public ScheduledJob reconcilePendingPaymentsJob(OrderCharges charges, PaymentGateway gateway,
            ConfirmPaymentUseCase confirm) {
        return job("payments.reconcile-pending", JobFrequency.EVERY_6_HOURS, () -> {
            for (OrderCharges.OpenCharge open : charges.openCharges()) {
                applyFirstApproved(open.id(), open.paymentIds(), gateway, confirm);
            }
        });
    }

    /**
     * Links com Pix aprovado e webhook perdido, links antigos e devoluções que não saíram ({@code reconcileLinks}).
     *
     * @param links pagamento de link
     * @return a tarefa
     */
    @Bean
    public ScheduledJob reconcileLinksJob(LinkPayments links) {
        return job("payments.reconcile-links", JobFrequency.EVERY_6_HOURS, links::reconcile);
    }

    /**
     * Tentativas de cartão sem resposta: procura o pagamento no Mercado Pago pela referência; aprovado confirma o
     * pedido ou o link, em análise passa para o job de análise, nenhum fecha a tentativa (G03).
     *
     * @param attempts tentativas
     * @param card     cobrança no cartão
     * @param charges  colunas de cobrança
     * @param confirm  confirmação única
     * @param links    links
     * @param linkPay  pagamento de link
     * @param clock    relógio
     * @return a tarefa
     */
    @Bean
    public ScheduledJob expireStaleAttemptsJob(PaymentAttempts attempts, CardChargeUseCase card,
            OrderCharges charges, ConfirmPaymentUseCase confirm, PayLinks links, LinkPayments linkPay, Clock clock) {
        return alongsideNode("payments.expire-stale-attempts", () -> {
            for (PaymentAttempts.Stale stale : attempts.stale(clock.instant().minus(STALE_AFTER))) {
                String ref = stale.shopOrderId() != null ? String.valueOf(stale.shopOrderId())
                        : LinkPayments.LINK_REF + stale.payLinkId();
                each("tentativa " + stale.id(), () -> card.recover(ref, List.of(stale.id()),
                        applier(stale.shopOrderId(), stale.payLinkId(), charges, confirm, links, linkPay)));
            }
        });
    }

    /**
     * Cartões que o Mercado Pago deixou em análise: aprovados confirmam o pedido ou o link; recusados fecham a
     * tentativa e avisam o cliente do pedido (o webhook pode não chegar).
     *
     * @param attempts tentativas
     * @param card     cobrança no cartão
     * @param charges  colunas de cobrança
     * @param confirm  confirmação única
     * @param links    links
     * @param linkPay  pagamento de link
     * @return a tarefa
     */
    @Bean
    public ScheduledJob settleReviewedCardsJob(PaymentAttempts attempts, CardChargeUseCase card,
            OrderCharges charges, ConfirmPaymentUseCase confirm, PayLinks links, LinkPayments linkPay) {
        return alongsideNode("payments.settle-reviewed-cards", () -> {
            for (PaymentAttempts.Open open : attempts.allOpen()) {
                each("tentativa " + open.id(), () -> card.refresh(open,
                        applier(open.shopOrderId(), open.payLinkId(), charges, confirm, links, linkPay), pay -> {
                            if (open.shopOrderId() != null) {
                                confirm.notifyCardRefused(open.shopOrderId(), CardFailReasons.of(pay.statusDetail()));
                            }
                        }));
            }
        });
    }

    /**
     * O que fazer com um cartão aprovado fora da requisição do cliente: confirma o pedido ou marca o link como pago.
     *
     * @param shopOrderId pedido (nulo se for link)
     * @param payLinkId   link (nulo se for pedido)
     * @param charges     colunas de cobrança
     * @param confirm     confirmação única
     * @param links       links
     * @param linkPay     pagamento de link
     * @return a ação
     */
    static Consumer<GatewayPayment> applier(Long shopOrderId, Long payLinkId, OrderCharges charges,
            ConfirmPaymentUseCase confirm, PayLinks links, LinkPayments linkPay) {
        return pay -> {
            if (shopOrderId != null) {
                charges.addPaymentId(shopOrderId, pay.id());
                confirm.confirm(shopOrderId, pay, null, "cartao");
            } else {
                links.addCardPayment(payLinkId, pay.id(), null);
                linkPay.markPaid(payLinkId, pay.id(), pay.amount());
            }
        };
    }

    /**
     * Roda o trabalho de um item: falha de um não impede os outros (o item volta na próxima rodada). Pagamento ainda
     * em análise não é falha.
     *
     * @param what o item (para o log)
     * @param work o trabalho
     */
    static void each(String what, Runnable work) {
        try {
            work.run();
        } catch (ConflictException e) {
            LOG.debug("{}: ainda em análise", what);
        } catch (RuntimeException e) {
            LOG.warn("{}: falhou; tenta na próxima rodada", what, e);
        }
    }

    /**
     * Apaga as entregas de webhook com mais de 30 dias.
     *
     * @param deliveries entregas
     * @return a tarefa
     */
    @Bean
    public ScheduledJob purgeWebhookDeliveriesJob(WebhookDeliveries deliveries) {
        return job("payments.purge-webhook-deliveries", JobFrequency.DAILY_AT_4AM,
                () -> deliveries.purgeOlderThanDays(KEEP_DELIVERIES_DAYS));
    }

    /**
     * Aplica o primeiro pagamento aprovado de um pedido; falha de um não impede os outros.
     *
     * @param orderId    pedido
     * @param paymentIds pagamentos
     * @param gateway    gateway
     * @param confirm    confirmação única
     */
    static void applyFirstApproved(long orderId, List<String> paymentIds, PaymentGateway gateway,
            ConfirmPaymentUseCase confirm) {
        for (String paymentId : paymentIds) {
            try {
                GatewayPayment p = gateway.getPayment(paymentId);
                if (p.approved() && confirm.confirm(orderId, p, null, "conciliacao").paid()) {
                    return;
                }
            } catch (RuntimeException e) {
                LOG.warn("Conciliação do pagamento {} do pedido {} falhou; tenta na próxima", paymentId, orderId, e);
            }
        }
    }

    /**
     * Tarefa com nome e frequência.
     *
     * @param name      nome
     * @param frequency frequência
     * @param work      trabalho
     * @return a tarefa
     */
    static ScheduledJob job(String name, JobFrequency frequency, Runnable work) {
        return new NamedJob(name, frequency, work, false);
    }

    /**
     * Tarefa de cartão de 5 em 5 minutos que roda mesmo com {@code JOBS_ENABLED=false}: só mexe em
     * {@code payment_attempts}, que o Node não conhece, e confirma pela confirmação única (idempotente).
     *
     * @param name nome
     * @param work trabalho
     * @return a tarefa
     */
    static ScheduledJob alongsideNode(String name, Runnable work) {
        return new NamedJob(name, JobFrequency.EVERY_5_MIN, work, true);
    }

    /**
     * Tarefa simples: nome, frequência, o trabalho e se roda junto com o Node.
     *
     * @param name              nome
     * @param frequency         frequência
     * @param work              trabalho
     * @param runsAlongsideNode se roda mesmo com as tarefas desligadas
     */
    record NamedJob(String name, JobFrequency frequency, Runnable work, boolean runsAlongsideNode)
            implements ScheduledJob {

        /** {@inheritDoc} */
        @Override
        public void run() {
            work.run();
        }
    }
}
