package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.payments.api.CardAvailability;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.CardFailReasons;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Cobrança no cartão, de pedido da loja ou de link de pagamento ({@code payWithCard} e {@code payLinkCard}): a
 * tentativa é registrada antes de chamar o gateway (G03), só as recusas contam para o limite (G04) e a aprovação
 * segue pelo caminho de quem chamou.
 */
@Service
public class CardChargeUseCase {

    /** Cartão desligado. */
    static final String UNAVAILABLE = "O pagamento com cartão não está disponível agora.";

    /** Outra tentativa em andamento (G03). */
    static final String IN_FLIGHT = "Já existe um pagamento em andamento para este pedido. Aguarde alguns segundos.";

    /** Limite de recusas (G04). */
    static final String TOO_MANY = "Muitas tentativas de cartão. Tente pagar com Pix ou fale com a gente.";

    /** Mercado Pago sem resposta. */
    static final String GATEWAY_DOWN = "Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague "
            + "com Pix.";

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Tentativas. */
    private final PaymentAttempts attempts;

    /** Limites. */
    private final RateLimiter limiter;

    /** Se o cartão está ligado (o app informa). */
    private final ObjectProvider<CardAvailability> availability;

    /** Alertas. */
    private final OpsAlert alert;

    /**
     * Cria o caso de uso.
     *
     * @param gateway      gateway
     * @param attempts     tentativas
     * @param limiter      limites
     * @param availability se o cartão está ligado
     * @param alert        alertas
     */
    public CardChargeUseCase(PaymentGateway gateway, PaymentAttempts attempts, RateLimiter limiter,
            ObjectProvider<CardAvailability> availability, OpsAlert alert) {
        this.gateway = gateway;
        this.attempts = attempts;
        this.limiter = limiter;
        this.availability = availability;
        this.alert = alert;
    }

    /**
     * Cobra o cartão.
     *
     * @param charge   a cobrança (a chave de idempotência é trocada pelo id da tentativa)
     * @param attempt  a tentativa (pedido ou link, cliente, IP)
     * @param approved o que fazer com o pagamento aprovado (confirmar o pedido ou marcar o link)
     * @param counted  o que fazer com o pagamento não aprovado (contar a tentativa; id nulo = recusa)
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    public Map<String, Object> charge(PaymentGateway.CardCharge charge, PaymentAttempts.Attempt attempt,
            Consumer<GatewayPayment> approved, Consumer<String> counted) {

        // ── 1. Cartão ligado e dentro do limite de recusas (G04).
        if (!Optional.ofNullable(availability.getIfAvailable()).map(CardAvailability::enabled).orElse(false)) {
            throw new DomainException(UNAVAILABLE, 503);
        }
        String customer = attempt.customerId() == null ? null : String.valueOf(attempt.customerId());
        if (customer != null && !limiter.peek(RateLimits.CARD_FAIL_CUSTOMER, customer)
                || !limiter.peek(RateLimits.CARD_FAIL_IP, attempt.clientIp())) {
            throw new TooManyRequestsException(TOO_MANY);
        }

        // ── 2. Uma tentativa em andamento por pedido (G03); o id dela é a chave de idempotência.
        if (!attempts.start(attempt)) {
            throw new ConflictException(IN_FLIGHT);
        }
        GatewayPayment pay = call(withKey(charge, attempt.id()), attempt.id());

        // ── 3. Desfecho da cobrança.
        if (pay.approved()) {
            attempts.finish(attempt.id(), "approved", pay.id());
            approved.accept(pay.amount().isZero() ? withAmount(pay, charge.amount()) : pay);
            return result(true, false, null);
        }
        if (pay.open()) {
            attempts.finish(attempt.id(), "pending", pay.id());
            counted.accept(pay.id());
            return result(false, true, null);
        }
        attempts.finish(attempt.id(), "rejected", pay.id());
        counted.accept(null);
        if (customer != null) {
            limiter.hit(RateLimits.CARD_FAIL_CUSTOMER, customer);
        }
        limiter.hit(RateLimits.CARD_FAIL_IP, attempt.clientIp());
        return result(false, false, CardFailReasons.of(pay.statusDetail()));
    }

    /**
     * Chama o gateway; sem resposta, a tentativa fica com erro (o job confere depois) e o cliente recebe 502.
     *
     * @param charge    cobrança
     * @param attemptId tentativa
     * @return o pagamento
     */
    private GatewayPayment call(PaymentGateway.CardCharge charge, UUID attemptId) {
        try {
            return gateway.createCard(charge);
        } catch (GatewayException e) {
            attempts.finish(attemptId, "error", null);
            alert.integrationDown("mercadopago-cartao", e);
            throw new DomainException(GATEWAY_DOWN, 502);
        }
    }

    /**
     * A cobrança com a chave de idempotência da tentativa.
     *
     * @param c  cobrança
     * @param id tentativa
     * @return a cobrança
     */
    private static PaymentGateway.CardCharge withKey(PaymentGateway.CardCharge c, UUID id) {
        return new PaymentGateway.CardCharge(c.reference(), id.toString(), c.amount(), c.description(), c.token(),
                c.installments(), c.methodId(), c.issuerId(), c.email(), c.firstName(), c.cpf());
    }

    /**
     * Pagamento aprovado sem o valor na resposta: vale o que foi cobrado.
     *
     * @param p      pagamento
     * @param amount valor cobrado
     * @return o pagamento
     */
    private static GatewayPayment withAmount(GatewayPayment p, Money amount) {
        return new GatewayPayment(p.id(), p.status(), p.statusDetail(), p.externalReference(), amount,
                p.refundedAmount(), p.qrCode(), p.qrBase64());
    }

    /**
     * Resposta como o Node: aprovado só {@code approved}; pendente sem {@code reason}.
     *
     * @param approved se aprovou
     * @param pending  se ficou pendente
     * @param reason   motivo da recusa
     * @return a resposta
     */
    static Map<String, Object> result(boolean approved, boolean pending, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approved", approved);
        if (!approved) {
            result.put("pending", pending);
        }
        if (reason != null) {
            result.put("reason", reason);
        }
        return result;
    }
}
