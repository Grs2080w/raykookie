package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cobrança dos pedidos da loja: a primeira, logo depois de criar o pedido ({@link ChargeStarter}), e o "Tentar outro
 * cartão" ({@code retryCard}).
 */
@Service
public class OrderPaymentUseCases implements ChargeStarter {

    /** Tentativas de cartão por pedido (evita testar cartão roubado contra um pedido real). */
    public static final int MAX_CARD_ATTEMPTS = 5;

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(OrderPaymentUseCases.class);

    /** Pix. */
    private final PixUseCases pix;

    /** Cartão. */
    private final CardChargeUseCase card;

    /** Confirmação única. */
    private final ConfirmPaymentUseCase confirm;

    /** Colunas de cobrança. */
    private final OrderCharges charges;

    /** Configuração. */
    private final PaymentSettings settings;

    /**
     * Cria os casos de uso.
     *
     * @param pix      Pix
     * @param card     cartão
     * @param confirm  confirmação única
     * @param charges  colunas de cobrança
     * @param settings configuração
     */
    public OrderPaymentUseCases(PixUseCases pix, CardChargeUseCase card, ConfirmPaymentUseCase confirm,
            OrderCharges charges, PaymentSettings settings) {
        this.pix = pix;
        this.card = card;
        this.confirm = confirm;
        this.charges = charges;
        this.settings = settings;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> startPix(NewOrder order) {
        if (!settings.configured()) {
            return null;
        }
        try {
            return pix.issue(order.id(), order.code(), order.total(), order.email(), order.name(), 1);
        } catch (RuntimeException e) {
            LOG.error("Falha ao gerar o Pix do pedido {}", order.id(), e);
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> startCard(NewOrder order, Card data, String clientIp) {
        try {
            return chargeOrder(order.id(), order.code(), order.total(), order.email(), order.name(), order.phone(),
                    order.customer(), data, clientIp);
        } catch (DomainException e) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("approved", false);
            refused.put("reason", e.getMessage());
            return refused;
        }
    }

    /**
     * "Tentar outro cartão": o pedido existe, sem pagamento.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @param data     cartão
     * @param clientIp IP real
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    public Map<String, Object> retryCard(long orderId, CustomerId customer, Card data, String clientIp) {

        // ── 1. Pedido do cliente, de cartão, não pago e não cancelado.
        OrderCharges.ChargeTarget order = charges.target(orderId, customer)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));
        if (!"cartao".equals(order.paymentMethod())) {
            throw new InvalidInputException("Este pedido não é pago com cartão.");
        }
        if (order.paid()) {
            return CardChargeUseCase.result(true, false, null);
        }
        if (!order.live()) {
            throw new InvalidInputException("Este pedido foi cancelado.");
        }

        // ── 2. Pagamento anterior em análise: aprovado, confirma; ainda aberto, barra a nova tentativa.
        Optional<Map<String, Object>> settled = card.settleOpen(order.id(), null, pay -> {
            charges.addPaymentId(order.id(), pay.id());
            confirm.confirm(order.id(), pay, null, "cartao");
        });
        if (settled.isPresent()) {
            return settled.get();
        }

        // ── 3. Limite por pedido e cobrança.
        if (order.cardAttempts() >= MAX_CARD_ATTEMPTS) {
            throw new TooManyRequestsException("Muitas tentativas de cartão para este pedido. Tente pagar com Pix "
                    + "ou fale com a gente.");
        }
        return chargeOrder(order.id(), order.code(), order.total(), order.email(), order.contactName(), order.phone(),
                customer, data, clientIp);
    }

    /**
     * Cobra o cartão de um pedido; aprovado, segue pela confirmação única.
     *
     * @param orderId  pedido
     * @param code     número que o cliente vê
     * @param total    total
     * @param email    e-mail
     * @param name     nome
     * @param phone    telefone
     * @param customer cliente
     * @param data     cartão
     * @param clientIp IP real
     * @return a resposta
     */
    private Map<String, Object> chargeOrder(long orderId, int code, Money total, String email, String name,
            String phone, CustomerId customer, Card data, String clientIp) {
        PaymentGateway.CardCharge charge = new PaymentGateway.CardCharge(String.valueOf(orderId), "", total,
                "Pedido #" + code + " - Raykookie", data.token(), data.installments(), data.methodId(),
                data.issuerId(), email, PixUseCases.firstName(name), data.cpf(),
                new PaymentGateway.Buyer(PixUseCases.lastName(name), phone, data.deviceId(), clientIp));
        PaymentAttempts.Attempt attempt = new PaymentAttempts.Attempt(UUID.randomUUID(), orderId, null,
                customer.value(), clientIp);
        return card.charge(charge, attempt, pay -> {
            charges.addPaymentId(orderId, pay.id());
            confirm.confirm(orderId, pay, data.installments(), "cartao");
        }, pending -> charges.countCardAttempt(orderId, pending));
    }
}
