package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementação de {@link PaymentsApi}.
 */
@Service
public class PaymentsService implements PaymentsApi {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(PaymentsService.class);

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Links de pagamento. */
    private final PayLinkUseCases links;

    /**
     * Cria o serviço.
     *
     * @param gateway gateway
     * @param links   links de pagamento
     */
    public PaymentsService(PaymentGateway gateway, PayLinkUseCases links) {
        this.gateway = gateway;
        this.links = links;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey) {
        return gateway.refund(paymentId, amountOrNull, idempotencyKey);
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayRefund> refunds(String paymentId) {
        return gateway.listRefunds(paymentId);
    }

    /** {@inheritDoc} */
    @Override
    public void cancelPendingCharges(List<String> paymentIds) {
        for (String id : paymentIds) {
            try {
                GatewayPayment payment = gateway.getPayment(id);
                if (payment.open()) {
                    gateway.cancel(id);
                }
            } catch (RuntimeException e) {
                LOG.debug("Pix {} não desativado (vence sozinho)", id, e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LinkGuest> linkGuest(String token) {
        return links.guest(token).map(g -> new LinkGuest(new CustomerId(g.customerId()), g.shopOrderId()));
    }
}
