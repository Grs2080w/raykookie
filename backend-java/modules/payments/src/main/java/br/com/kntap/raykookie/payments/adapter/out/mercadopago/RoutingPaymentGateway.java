package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.payments.api.GatewayRefund;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * O gateway que o resto do sistema enxerga. Cobranças <b>novas</b> vão para a API escolhida em {@code MP_API}
 * ({@code payments} = API Payments, o padrão; {@code orders} = API Orders). Consultas, cancelamentos e devoluções
 * seguem o <b>formato do id</b>: {@code ORD…} é pedido da API Orders; o resto (numérico) é pagamento da API Payments.
 * Assim os pagamentos antigos continuam funcionando depois de trocar a API.
 */
@Primary
@Component
public class RoutingPaymentGateway implements PaymentGateway {

    /** API Payments (ids numéricos). */
    private final MercadoPagoClient payments;

    /** API Orders (ids {@code ORD…}). */
    private final MercadoPagoOrdersClient orders;

    /** Para onde vão as cobranças novas. */
    private final PaymentGateway fresh;

    /** {@code true} se as cobranças novas vão para a API Orders. */
    private final boolean ordersForNew;

    /**
     * Cria o roteador.
     *
     * @param payments adaptador da API Payments
     * @param orders   adaptador da API Orders
     * @param api      {@code MP_API}: {@code payments} (padrão) ou {@code orders}
     */
    public RoutingPaymentGateway(MercadoPagoClient payments, MercadoPagoOrdersClient orders,
            @Value("${rayka.mp.api:payments}") String api) {
        if (!"payments".equals(api) && !"orders".equals(api)) {
            throw new IllegalStateException("MP_API precisa ser payments ou orders (veio '" + api + "')");
        }
        this.payments = payments;
        this.orders = orders;
        this.ordersForNew = "orders".equals(api);
        this.fresh = ordersForNew ? orders : payments;
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createPix(PixCharge charge) {
        return fresh.createPix(charge);
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment createCard(CardCharge charge) {
        return fresh.createCard(charge);
    }

    /** {@inheritDoc} */
    @Override
    public GatewayPayment getPayment(String id) {
        return of(id).getPayment(id);
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(String id) {
        of(id).cancel(id);
    }

    /** {@inheritDoc} */
    @Override
    public GatewayRefund refund(String paymentId, Money amountOrNull, String idempotencyKey) {
        return of(paymentId).refund(paymentId, amountOrNull, idempotencyKey);
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayRefund> listRefunds(String paymentId) {
        return of(paymentId).listRefunds(paymentId);
    }

    /** {@inheritDoc} */
    @Override
    public List<GatewayPayment> search(String externalReference) {
        List<GatewayPayment> found = new ArrayList<>(payments.search(externalReference));
        if (ordersForNew) {
            found.addAll(orders.search(externalReference));
        }
        return found;
    }

    /**
     * Adaptador dono do id.
     *
     * @param id id do pagamento ou do pedido
     * @return a API Orders se o id começa com {@code ORD}; a API Payments no resto
     */
    private PaymentGateway of(String id) {
        return id != null && id.startsWith(MercadoPagoOrdersClient.ID_PREFIX) ? orders : payments;
    }
}
