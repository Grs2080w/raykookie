package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.payments.application.ports.PayLinks;
import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.application.ports.WebhookDeliveries;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tarefas agendadas de pagamentos: conciliação dos Pix e dos links (a cada 6 h, como o Node), tentativas de cartão
 * sem resposta (a cada 5 min) e limpeza das entregas de webhook (diária).
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
     * Tentativas de cartão sem resposta: libera o pedido e aplica a cobrança que tiver sido aprovada (G03).
     *
     * @param attempts tentativas
     * @param gateway  gateway
     * @param charges  colunas de cobrança
     * @param confirm  confirmação única
     * @param links    links
     * @param linkPay  pagamento de link
     * @param clock    relógio
     * @return a tarefa
     */
    @Bean
    public ScheduledJob expireStaleAttemptsJob(PaymentAttempts attempts, PaymentGateway gateway,
            OrderCharges charges, ConfirmPaymentUseCase confirm, PayLinks links, LinkPayments linkPay, Clock clock) {
        return job("payments.expire-stale-attempts", JobFrequency.EVERY_5_MIN, () -> {
            for (PaymentAttempts.Stale stale : attempts.stale(clock.instant().minus(STALE_AFTER))) {
                String ref = stale.shopOrderId() != null ? String.valueOf(stale.shopOrderId())
                        : LinkPayments.LINK_REF + stale.payLinkId();
                GatewayPayment approved = gateway.search(ref).stream().filter(GatewayPayment::approved).findFirst()
                        .orElse(null);
                if (approved == null) {
                    attempts.finish(stale.id(), "error", null);
                } else if (stale.shopOrderId() != null) {
                    charges.addPaymentId(stale.shopOrderId(), approved.id());
                    confirm.confirm(stale.shopOrderId(), approved, null, "cartao");
                    attempts.finish(stale.id(), "approved", approved.id());
                } else {
                    links.addCardPayment(stale.payLinkId(), approved.id(), null);
                    linkPay.markPaid(stale.payLinkId(), approved.id(), approved.amount());
                    attempts.finish(stale.id(), "approved", approved.id());
                }
            }
        });
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
        return new NamedJob(name, frequency, work);
    }

    /**
     * Tarefa simples: nome, frequência e o trabalho.
     *
     * @param name      nome
     * @param frequency frequência
     * @param work      trabalho
     */
    record NamedJob(String name, JobFrequency frequency, Runnable work) implements ScheduledJob {

        /** {@inheritDoc} */
        @Override
        public void run() {
            work.run();
        }
    }
}
