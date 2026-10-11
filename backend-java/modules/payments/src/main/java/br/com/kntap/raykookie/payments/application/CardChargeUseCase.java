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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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

    /** Pagamento anterior ainda em análise: outra tentativa poderia cobrar duas vezes. */
    static final String IN_REVIEW = "Seu pagamento anterior ainda está em análise no Mercado Pago. Nenhuma cobrança "
            + "foi confirmada: se ele for recusado, nada é cobrado. Aguarde a resposta antes de tentar de novo.";

    /** Quantas vezes perguntar de novo ao Mercado Pago quando ele responde "em análise". */
    static final int REVIEW_POLLS = 4;

    /** Mercado Pago sem resposta. */
    static final String GATEWAY_DOWN = "Não conseguimos falar com o Mercado Pago agora. Tente de novo ou pague "
            + "com Pix.";

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(CardChargeUseCase.class);

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

    /** Espera entre as perguntas ao Mercado Pago sobre um pagamento em análise. */
    private final Duration reviewPoll;

    /**
     * Cria o caso de uso.
     *
     * @param gateway      gateway
     * @param attempts     tentativas
     * @param limiter      limites
     * @param availability se o cartão está ligado
     * @param alert        alertas
     * @param reviewPoll   espera entre as perguntas sobre um pagamento em análise ({@code rayka.mp.review-poll})
     */
    public CardChargeUseCase(PaymentGateway gateway, PaymentAttempts attempts, RateLimiter limiter,
            ObjectProvider<CardAvailability> availability, OpsAlert alert,
            @Value("${rayka.mp.review-poll:PT2S}") Duration reviewPoll) {
        this.gateway = gateway;
        this.attempts = attempts;
        this.limiter = limiter;
        this.availability = availability;
        this.alert = alert;
        this.reviewPoll = reviewPoll;
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
        GatewayPayment pay;
        try {
            pay = awaitReview(gateway.createCard(withKey(charge, attempt.id())));
        } catch (GatewayException e) {
            return failed(e, attempt, counted);
        }

        // ── 3. Desfecho da cobrança (a análise do antifraude costuma sair em segundos).
        if (pay.approved()) {
            apply(attempt.id(), pay.amount().isZero() ? withAmount(pay, charge.amount()) : pay, approved);
            return result(true, false, null);
        }
        if (pay.open()) {
            attempts.finish(attempt.id(), "pending", pay.id());
            counted.accept(pay.id());
            return result(false, true, null);
        }
        return refused(pay, attempt, counted);
    }

    /**
     * Cartão recusado: fecha a tentativa, conta no pedido e no limite de recusas (G04) e diz o motivo.
     *
     * @param pay     o pagamento recusado
     * @param attempt tentativa
     * @param counted o que fazer com o pagamento não aprovado
     * @return a recusa
     */
    private Map<String, Object> refused(GatewayPayment pay, PaymentAttempts.Attempt attempt,
            Consumer<String> counted) {

        // ── 1. Tentativa e pedido.
        attempts.finish(attempt.id(), "rejected", pay.id());
        counted.accept(null);

        // ── 2. Limite de recusas por cliente e por IP.
        if (attempt.customerId() != null) {
            limiter.hit(RateLimits.CARD_FAIL_CUSTOMER, String.valueOf(attempt.customerId()));
        }
        limiter.hit(RateLimits.CARD_FAIL_IP, attempt.clientIp());
        return result(false, false, CardFailReasons.of(pay.statusDetail()));
    }

    /**
     * Antes de outra tentativa no mesmo pedido ou link: pergunta ao Mercado Pago pelas que ficaram em análise. Uma
     * aprovada segue pelo caminho da aprovação; uma ainda em análise barra a nova (duas poderiam ser cobradas);
     * as recusadas são fechadas e a nova tentativa segue.
     *
     * @param shopOrderId pedido (nulo se for link)
     * @param payLinkId   link (nulo se for pedido)
     * @param approved    o que fazer com o pagamento aprovado
     * @return a resposta de aprovado, ou vazio para seguir com a nova tentativa
     * @throws ConflictException se ainda houver pagamento em análise
     */
    public Optional<Map<String, Object>> settleOpen(Long shopOrderId, Long payLinkId,
            Consumer<GatewayPayment> approved) {

        // ── 1. Tentativas em análise.
        for (PaymentAttempts.Open open : attempts.open(shopOrderId, payLinkId)) {
            switch (refresh(open, approved)) {
                case "approved" -> {
                    return Optional.of(result(true, false, null));
                }
                case "open" -> throw new ConflictException(IN_REVIEW);
                default -> {
                    // recusada: fechada, segue para a próxima
                }
            }
        }

        // ── 2. Tentativas que ficaram sem resposta: o Mercado Pago pode ter criado o pagamento mesmo assim.
        List<UUID> unanswered = attempts.unanswered(shopOrderId, payLinkId);
        if (unanswered.isEmpty()) {
            return Optional.empty();
        }
        String reference = shopOrderId != null ? String.valueOf(shopOrderId) : LinkPayments.LINK_REF + payLinkId;
        return recover(reference, unanswered, approved);
    }

    /**
     * Procura no Mercado Pago, pela referência, o pagamento de tentativas que ficaram sem resposta (tempo esgotado ou
     * erro do servidor dele) e fecha essas tentativas com o que achar.
     *
     * @param reference  referência externa (pedido ou {@code link:<id>})
     * @param unanswered tentativas sem resposta
     * @param approved   o que fazer com o pagamento aprovado
     * @return a resposta de aprovado, ou vazio para seguir com a nova tentativa
     * @throws ConflictException se o pagamento achado estiver em análise
     * @throws DomainException   (502) se o Mercado Pago continuar sem responder
     */
    public Optional<Map<String, Object>> recover(String reference, List<UUID> unanswered,
            Consumer<GatewayPayment> approved) {

        // ── 1. Pagamentos com a referência; sem resposta, não arrisca cobrar de novo.
        List<GatewayPayment> found;
        try {
            found = gateway.search(reference);
        } catch (GatewayException e) {
            throw new DomainException(GATEWAY_DOWN, 502);
        }
        UUID first = unanswered.getFirst();

        // ── 2. Aprovado: aplica; em análise: espera; nada: as tentativas não cobraram.
        Optional<GatewayPayment> paid = found.stream().filter(GatewayPayment::approved).findFirst();
        Optional<GatewayPayment> open = found.stream().filter(GatewayPayment::open).findFirst();
        if (paid.isPresent()) {
            apply(first, paid.get(), approved);
        } else if (open.isPresent()) {
            attempts.finish(first, "pending", open.get().id());
        }
        unanswered.stream().skip(paid.isPresent() || open.isPresent() ? 1 : 0)
                .forEach(id -> attempts.finish(id, "rejected", null));

        // ── 3. Em análise barra a nova tentativa; aprovado responde como aprovado.
        if (open.isPresent() && paid.isEmpty()) {
            throw new ConflictException(IN_REVIEW);
        }
        return paid.map(p -> result(true, false, null));
    }

    /**
     * Pergunta ao Mercado Pago por uma tentativa em análise e registra o desfecho.
     *
     * @param open     a tentativa
     * @param approved o que fazer com o pagamento aprovado
     * @return {@code approved}, {@code open} (ainda em análise ou sem resposta) ou {@code rejected}
     */
    public String refresh(PaymentAttempts.Open open, Consumer<GatewayPayment> approved) {
        return refresh(open, approved, pay -> { });
    }

    /**
     * Pergunta ao Mercado Pago por uma tentativa em análise e registra o desfecho, avisando quem chamou da recusa.
     *
     * @param open     a tentativa
     * @param approved o que fazer com o pagamento aprovado
     * @param refused  o que fazer com o pagamento recusado depois da análise
     * @return {@code approved}, {@code open} (ainda em análise ou sem resposta) ou {@code rejected}
     */
    public String refresh(PaymentAttempts.Open open, Consumer<GatewayPayment> approved,
            Consumer<GatewayPayment> refused) {

        // ── 1. Situação atual; sem resposta, continua em análise.
        GatewayPayment pay;
        try {
            pay = gateway.getPayment(open.gatewayId());
        } catch (GatewayException e) {
            return "open";
        }

        // ── 2. Aprovado confirma; em análise segue esperando; recusado fecha a tentativa e avisa.
        if (pay.approved()) {
            apply(open.id(), pay, approved);
            return "approved";
        }
        if (pay.open()) {
            return "open";
        }
        attempts.finish(open.id(), "rejected", pay.id());
        refused.accept(pay);
        return "rejected";
    }

    /**
     * Pagamento "em análise": pergunta de novo algumas vezes, para o cliente já sair com a resposta certa. Sem
     * resposta, fica como estava (a conciliação resolve depois).
     *
     * @param pay a resposta da cobrança
     * @return a resposta mais recente
     */
    private GatewayPayment awaitReview(GatewayPayment pay) {
        GatewayPayment current = pay;
        for (int i = 0; i < REVIEW_POLLS && current.open(); i++) {

            // ── 1. Espera um pouco.
            try {
                Thread.sleep(reviewPoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }

            // ── 2. Pergunta de novo.
            try {
                GatewayPayment fresh = gateway.getPayment(pay.id());
                current = fresh.amount().isZero() ? withAmount(fresh, pay.amount()) : fresh;
            } catch (GatewayException e) {
                return current;
            }
        }
        return current;
    }

    /**
     * O Mercado Pago não cobrou. Erro nos dados (4xx: token vencido ou já usado, CPF, parcelas) é recusa com o
     * motivo, sem contar para o limite de recusas; sem resposta ou erro dele (5xx, credencial), a tentativa fica com
     * erro (o pagamento pode ter sido criado; o job e a próxima tentativa conferem) e o cliente recebe 502.
     *
     * @param e       o erro
     * @param attempt tentativa
     * @param counted o que fazer com o pagamento não aprovado
     * @return a recusa
     * @throws DomainException (502) se o Mercado Pago não respondeu
     */
    private Map<String, Object> failed(GatewayException e, PaymentAttempts.Attempt attempt,
            Consumer<String> counted) {

        // ── 1. Dados do cartão recusados pela API: nada foi criado.
        if (CardFailReasons.isRequestError(e.status())) {
            attempts.finish(attempt.id(), "rejected", null);
            counted.accept(null);
            LOG.warn("Mercado Pago recusou os dados do cartão (HTTP {}): {}", e.status(), e.getMessage());
            return result(false, false, CardFailReasons.ofRequestError(e.body()));
        }

        // ── 2. Sem resposta: talvez tenha cobrado.
        attempts.finish(attempt.id(), "error", null);
        alert.integrationDown("mercadopago-cartao", e);
        throw new DomainException(GATEWAY_DOWN, 502);
    }

    /**
     * Pagamento aprovado: confirma o pedido ou o link e só então fecha a tentativa. Se a confirmação falhar, a
     * tentativa fica em análise com o pagamento, e o job de 5 em 5 minutos confirma de novo (a confirmação é
     * idempotente). O cliente ouve "aprovado", que é a verdade: o dinheiro foi aprovado.
     *
     * @param attemptId tentativa
     * @param pay       pagamento aprovado
     * @param approved  o que fazer com o pagamento aprovado
     */
    private void apply(UUID attemptId, GatewayPayment pay, Consumer<GatewayPayment> approved) {
        try {
            approved.accept(pay);
            attempts.finish(attemptId, "approved", pay.id());
        } catch (RuntimeException e) {
            LOG.error("Cartão {} aprovado, mas a confirmação falhou; o job tenta de novo", pay.id(), e);
            attempts.finish(attemptId, "pending", pay.id());
            alert.paymentProblem("cartão aprovado sem confirmar o pedido; o job tenta de novo",
                    Map.of("attempt", attemptId.toString(), "payment", pay.id()));
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
                c.installments(), c.methodId(), c.issuerId(), c.email(), c.firstName(), c.cpf(), c.buyer());
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
