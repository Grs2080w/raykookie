package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.orders.api.ports.PendingChargeCanceller;
import br.com.kntap.raykookie.payments.application.ports.OrderCharges;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.ConfirmationOutcome;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pix dos pedidos da loja ({@code backend/lib/pix.ts}): o primeiro, o "Gerar novo Pix" e a conferência do Pix
 * pendente antes de cancelar (G09: Pix pago na hora passa pela confirmação única).
 */
@Service
public class PixUseCases implements PendingChargeCanceller {

    /** Pix gerados por pedido (evita abuso). */
    public static final int MAX_PIX_ATTEMPTS = 10;

    /** Pix indisponível. */
    static final String UNAVAILABLE = "O Pix não está disponível agora. Tente de novo mais tarde.";

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(PixUseCases.class);

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Colunas de cobrança. */
    private final OrderCharges charges;

    /** Confirmação única. */
    private final ConfirmPaymentUseCase confirm;

    /** Configuração. */
    private final PaymentSettings settings;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param gateway  gateway
     * @param charges  colunas de cobrança
     * @param confirm  confirmação única
     * @param settings configuração
     * @param clock    relógio
     */
    public PixUseCases(PaymentGateway gateway, OrderCharges charges, ConfirmPaymentUseCase confirm,
            PaymentSettings settings, Clock clock) {
        this.gateway = gateway;
        this.charges = charges;
        this.confirm = confirm;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Gera um Pix para o pedido e grava ({@code issuePix}).
     *
     * @param orderId pedido
     * @param code    número que o cliente vê
     * @param total   total
     * @param email   e-mail do cliente
     * @param name    nome
     * @param attempt número do Pix
     * @return {@code code}, {@code qr_base64} e {@code expires_in}
     */
    public Map<String, Object> issue(long orderId, int code, Money total, String email, String name, int attempt) {
        Instant expiresAt = clock.instant().plus(settings.pixValidity());
        GatewayPayment pay = gateway.createPix(new PaymentGateway.PixCharge(String.valueOf(orderId),
                "rayka-order-" + orderId + "-" + attempt, total, "Pedido #" + code + " - Raykookie", email,
                firstName(name), expiresAt));
        charges.savePix(orderId, pay.id(), pay.qrCode(), nonNull(pay.qrBase64()), expiresAt, attempt);
        return pix(pay.qrCode(), nonNull(pay.qrBase64()), expiresAt);
    }

    /**
     * "Gerar novo Pix": só troca o código depois que o atual venceu, e confere antes se o atual não foi pago.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @return {@code {"ok":true,"state":…}} e o Pix
     */
    public Map<String, Object> regenerate(long orderId, CustomerId customer) {

        // ── 1. Pedido do cliente, com Pix, não pago e não cancelado.
        if (!settings.configured()) {
            throw new DomainException(UNAVAILABLE, 503);
        }
        OrderCharges.ChargeTarget order = charges.target(orderId, customer)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado."));
        if (!"pix".equals(order.paymentMethod())) {
            throw new InvalidInputException("Este pedido não é pago com Pix.");
        }
        if (order.paid()) {
            return state("paid", null);
        }
        if (!order.live()) {
            throw new InvalidInputException("Este pedido foi cancelado.");
        }

        return renew(order);
    }

    /**
     * Devolve o Pix que ainda vale ou gera outro (confere antes se o anterior foi pago).
     *
     * @param order pedido
     * @return o estado e o Pix
     */
    private Map<String, Object> renew(OrderCharges.ChargeTarget order) {

        // ── 1. Pix atual ainda vale: não troca.
        Duration left = order.pixExpiresAt() == null ? Duration.ZERO
                : Duration.between(clock.instant(), order.pixExpiresAt());
        if (!order.pixQrCode().isEmpty() && left.isPositive()) {
            return state("active", Map.of("code", order.pixQrCode(), "qr_base64", "", "expires_in",
                    left.toSeconds()));
        }
        if (order.pixAttempts() >= MAX_PIX_ATTEMPTS) {
            throw new TooManyRequestsException("Muitos Pix gerados para este pedido. Fale com a gente.");
        }

        // ── 2. O Pix anterior pode ter sido pago no último instante.
        if (order.mpPaymentId() != null && paidMeanwhile(order.id(), order.mpPaymentId())) {
            return state("paid", null);
        }

        // ── 3. Gera o novo.
        try {
            return state("renewed", issue(order.id(), order.code(), order.total(), order.email(), order.contactName(),
                    order.pixAttempts() + 1));
        } catch (RuntimeException e) {
            LOG.error("Falha ao gerar novo Pix do pedido {}", order.id(), e);
            throw new DomainException("Não conseguimos gerar o Pix agora. Tente de novo em instantes.", 502);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean settleBeforeCancel(long orderId, String mpPaymentId) {
        GatewayPayment payment = gateway.getPayment(mpPaymentId);
        if (payment.approved()) {
            return confirm.confirm(orderId, payment, null, "pix").paid();
        }
        if (payment.open()) {
            gateway.cancel(mpPaymentId);
        }
        return false;
    }

    /**
     * Confere o Pix anterior: aprovado passa pela confirmação; pendente é desativado. Sem resposta, segue.
     *
     * @param orderId   pedido
     * @param paymentId Pix anterior
     * @return verdadeiro se o pedido ficou pago
     */
    private boolean paidMeanwhile(long orderId, String paymentId) {
        try {
            GatewayPayment old = gateway.getPayment(paymentId);
            if (old.approved()) {
                ConfirmationOutcome outcome = confirm.confirm(orderId, old, null, "pix");
                return outcome.paid();
            }
            if (old.open()) {
                gateway.cancel(paymentId);
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui conferir o Pix anterior do pedido {}", orderId, e);
        }
        return false;
    }

    /**
     * Resposta {@code {"ok":true,"state":…,"pix":…}}.
     *
     * @param state estado
     * @param pix   Pix (nulo = sem)
     * @return a resposta
     */
    private static Map<String, Object> state(String state, Map<String, Object> pix) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("state", state);
        if (pix != null) {
            result.put("pix", pix);
        }
        return result;
    }

    /**
     * O Pix para a resposta.
     *
     * @param code      copia e cola
     * @param qrBase64  QR
     * @param expiresAt validade
     * @return {@code code}, {@code qr_base64} e {@code expires_in}
     */
    private Map<String, Object> pix(String code, String qrBase64, Instant expiresAt) {
        Map<String, Object> pix = new LinkedHashMap<>();
        pix.put("code", code);
        pix.put("qr_base64", qrBase64);
        pix.put("expires_in", Math.floorDiv(Duration.between(clock.instant(), expiresAt).toMillis(), 1000));
        return pix;
    }

    /**
     * Primeiro nome ({@code name.split(' ')[0]}).
     *
     * @param name nome
     * @return o primeiro nome
     */
    static String firstName(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /**
     * Sobrenome: o que vem depois do primeiro nome (vazio se o nome for uma palavra só).
     *
     * @param name nome
     * @return o sobrenome
     */
    static String lastName(String name) {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? "" : trimmed.substring(space + 1).trim();
    }

    /**
     * Nulo vira vazio.
     *
     * @param text texto
     * @return o texto
     */
    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
