package br.com.kntap.raykookie.payments.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import br.com.kntap.raykookie.payments.application.ports.PayLinks;
import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.payments.application.ports.PaymentGateway;
import br.com.kntap.raykookie.payments.domain.GatewayPayment;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Link de pagamento de pedido registrado à mão ({@code backend/lib/pay-link.ts}): a dona gera, o cliente abre a
 * página pública, paga com Pix ou cartão e, pago, cancela ou pede devolução pelo mesmo link.
 */
@Service
public class PayLinkUseCases {

    /** Link inexistente. */
    public static final String NOT_FOUND = "Link não encontrado.";

    /** Token: 32 bytes em base64url = 43 caracteres. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Sorteio do token. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(PayLinkUseCases.class);

    /** Links. */
    private final PayLinks links;

    /** Pagamento aplicado. */
    private final LinkPayments payments;

    /** Gateway. */
    private final PaymentGateway gateway;

    /** Cartão. */
    private final CardChargeUseCase card;

    /** Pedidos do cliente. */
    private final OrdersApi orders;

    /** Configuração. */
    private final PaymentSettings settings;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param links    links
     * @param payments pagamento aplicado
     * @param gateway  gateway
     * @param card     cartão
     * @param orders   pedidos
     * @param settings configuração
     * @param clock    relógio
     */
    public PayLinkUseCases(PayLinks links, LinkPayments payments, PaymentGateway gateway, CardChargeUseCase card,
            OrdersApi orders, PaymentSettings settings, Clock clock) {

        // ── 1. Links e cobrança.
        this.links = links;
        this.payments = payments;
        this.gateway = gateway;
        this.card = card;

        // ── 2. Pedidos, configuração e relógio.
        this.orders = orders;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Diz se o texto tem o formato de um token.
     *
     * @param token texto
     * @return verdadeiro se tiver
     */
    public static boolean isToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /**
     * Cria (ou reaproveita) o link de uma linha registrada à mão.
     *
     * @param owner   dona
     * @param orderId linha
     * @param amount  valor (nulo = total da linha)
     * @return {@code url} e {@code amount}
     */
    public Map<String, Object> ensure(OwnerId owner, long orderId, Money amount) {

        // ── 1. A linha: da dona, à mão, aberta e não paga.
        PayLinks.LinkOrder o = links.order(orderId, owner).orElseThrow(() -> new NotFoundException(
                "Pedido não encontrado."));
        if (o.shopOrderId() != null) {
            throw new InvalidInputException("Pedido do app: o cliente paga pelo próprio app.");
        }
        if ("cancelado".equals(o.status())) {
            throw new ConflictException("Este pedido foi cancelado.");
        }
        if ("pago".equals(o.payment())) {
            throw new ConflictException("Este pedido já está pago.");
        }

        // ── 2. Valor de R$ 0,01 até o total.
        Money total = Money.fromDecimal(o.total().setScale(2, java.math.RoundingMode.HALF_UP));
        Money value = amount == null ? total : amount;
        if (value.cents() < 1 || total.isLessThan(value)) {
            throw new InvalidInputException("O valor precisa ficar entre R$ 0,01 e R$ " + total.toDecimal()
                    .toPlainString().replace('.', ',') + ".");
        }
        if (!settings.configured()) {
            throw new DomainException(PixUseCases.UNAVAILABLE, 503);
        }

        // ── 3. Grava; valor novo cancela o Pix do valor antigo.
        Optional<PayLinks.Previous> prev = links.previous(orderId);
        if (prev.isPresent() && prev.get().paid()) {
            throw new ConflictException("Este pedido já foi pago pelo link.");
        }
        String token = links.upsert(orderId, newToken(), value)
                .orElseThrow(() -> new ConflictException("Este pedido já foi pago pelo link."));
        prev.filter(p -> !p.amount().equals(value) && p.mpPaymentId() != null).ifPresent(p -> cancelQuietly(
                p.mpPaymentId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", settings.siteUrl("/pagar/" + token));
        result.put("amount", NodeRows.number(value.toDecimal()));
        return result;
    }

    /**
     * O que a página pública mostra. Só lê: abrir o link (inclusive a prévia do WhatsApp) nunca gera Pix.
     *
     * @param token token
     * @return a página
     */
    public Map<String, Object> view(String token) {

        // ── 1. O link e o estado.
        PayLinks.Link l = link(token);
        String state = l.paidAt() != null || "pago".equals(l.payment()) ? "pago"
                : "cancelado".equals(l.status()) ? "cancelado" : "aberto";
        long left = l.pixExpiresAt() == null ? 0
                : Math.max(0, Math.floorDiv(Duration.between(clock.instant(), l.pixExpiresAt()).toMillis(), 1000));

        // ── 2. A linha, o Pix e o pedido do app (se já pago).
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("number", l.number());
        order.put("customer", l.customer());
        order.put("product", l.product());
        order.put("qty", NodeRows.number(l.qty()));
        order.put("unit_price", NodeRows.number(l.unitPrice()));
        order.put("delivery_date", l.deliveryDate());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("state", state);
        view.put("amount", NodeRows.number(l.amount().toDecimal()));
        view.put("order", order);
        view.put("pix", l.pixQrCode().isEmpty() ? null : pix(l.pixQrCode(), l.pixQrBase64(), left, left <= 0));
        view.put("shop_order", l.paidAt() == null ? null : guest(token).flatMap(g -> orders.customerOrder(
                new CustomerId(g.customerId()), g.shopOrderId())).orElse(null));
        return view;
    }

    /**
     * Convidado dono do pedido de um link PAGO (promove na hora um link antigo).
     *
     * @param token token
     * @return o cliente e o pedido, ou vazio (token ruim, inexistente ou não pago)
     */
    public Optional<GuestOrder> guest(String token) {
        if (!isToken(token)) {
            return Optional.empty();
        }
        Optional<PayLinks.Guest> g = links.guest(token).filter(PayLinks.Guest::paid);
        if (g.isPresent() && g.get().shopOrderId() == null) {
            payments.promoteLegacy(g.get().linkId());
            g = links.guest(token);
        }
        return g.filter(x -> x.shopOrderId() != null && x.customerId() != null)
                .map(x -> new GuestOrder(x.customerId(), x.shopOrderId()));
    }

    /**
     * Cancela, pelo link, o pedido pago e ainda não iniciado (mesma regra do app).
     *
     * @param token token
     * @param body  {@code reason_code} e {@code reason}
     * @return o resultado do cancelamento
     */
    public Map<String, Object> cancel(String token, Map<String, Object> body) {
        GuestOrder g = guest(token).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        return orders.cancelByCustomer(g.shopOrderId(), new CustomerId(g.customerId()), body);
    }

    /**
     * "Pagar com Pix" da página pública: o mesmo Pix enquanto vale; vencido, confere o antigo antes de trocar.
     *
     * @param token token
     * @return {@code {"ok":true,"state":…,"pix":…}}
     */
    public Map<String, Object> pix(String token) {

        // ── 1. Link aberto e com o valor ainda válido.
        if (!isToken(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        if (!settings.configured()) {
            throw new DomainException(PixUseCases.UNAVAILABLE, 503);
        }
        PayLinks.Link l = link(token);
        if (l.paidAt() != null || "pago".equals(l.payment())) {
            return state("paid", null);
        }
        openAndSameValue(l);

        // ── 2. Pix ainda válido: o mesmo.
        Duration left = l.pixExpiresAt() == null ? Duration.ZERO : Duration.between(clock.instant(),
                l.pixExpiresAt());
        if (!l.pixQrCode().isEmpty() && left.isPositive()) {
            return state("active", pix(l.pixQrCode(), l.pixQrBase64(), Math.floorDiv(left.toMillis(), 1000), false));
        }
        if (l.pixAttempts() >= PixUseCases.MAX_PIX_ATTEMPTS) {
            throw new TooManyRequestsException("Muitos Pix gerados para este pedido. Fale com a gente.");
        }

        // ── 3. O anterior pode ter sido pago no último instante.
        if (l.mpPaymentId() != null && paidMeanwhile(l)) {
            return state("paid", null);
        }
        return state("renewed", issue(l));
    }

    /**
     * "Pagar com cartão" da página pública.
     *
     * @param token    token
     * @param data     cartão
     * @param clientIp IP real
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    public Map<String, Object> card(String token, ChargeStarter.Card data, String clientIp) {

        // ── 1. Link aberto, valor válido, sem pagamento em análise e dentro do limite por link.
        if (!isToken(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        PayLinks.Link l = link(token);
        if (l.paidAt() != null || "pago".equals(l.payment())) {
            return CardChargeUseCase.result(true, false, null);
        }
        openAndSameValue(l);
        Optional<Map<String, Object>> settled = card.settleOpen(null, l.id(), pay -> {
            links.addCardPayment(l.id(), pay.id(), null);
            payments.markPaid(l.id(), pay.id(), l.amount());
        });
        if (settled.isPresent()) {
            return settled.get();
        }
        if (l.cardAttempts() >= OrderPaymentUseCases.MAX_CARD_ATTEMPTS) {
            throw new TooManyRequestsException("Muitas tentativas de cartão para este pedido. Peça para pagar com "
                    + "Pix.");
        }

        // ── 2. Cobra; aprovado, marca o link pelo mesmo caminho do Pix.
        PaymentGateway.CardCharge charge = new PaymentGateway.CardCharge(LinkPayments.LINK_REF + l.id(), "",
                l.amount(), "Pedido #" + l.number() + " - Raykookie", data.token(), data.installments(),
                data.methodId(), data.issuerId(), settings.payerEmail(), PixUseCases.firstName(l.customer()), data
                        .cpf(), new PaymentGateway.Buyer(PixUseCases.lastName(l.customer()), null, data.deviceId(),
                                clientIp), data.typeId());
        return card.charge(charge, new PaymentAttempts.Attempt(UUID.randomUUID(), null, l.id(), null, clientIp),
                pay -> {
            links.addCardPayment(l.id(), pay.id(), data.installments());
            if (payments.markPaid(l.id(), pay.id(), l.amount()).isEmpty()) {
                LOG.warn("Cartão {} aprovado do link {} não aplicado (já pago por outro meio)", pay.id(), l.id());
            }
        }, pending -> links.countCardAttempt(l.id(), pending));
    }

    /**
     * O link pelo token.
     *
     * @param token token
     * @return o link
     */
    private PayLinks.Link link(String token) {
        if (!isToken(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return links.byToken(token).orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    /**
     * Linha não cancelada e sem valor reduzido depois de compartilhar.
     *
     * @param l link
     */
    private static void openAndSameValue(PayLinks.Link l) {
        if ("cancelado".equals(l.status())) {
            throw new ConflictException("Este pedido foi cancelado.");
        }
        if (l.lineTotal().isLessThan(l.amount())) {
            throw new ConflictException("O valor deste pedido mudou. Peça um novo link para a loja.");
        }
    }

    /**
     * Confere o Pix anterior: aprovado marca o link; pendente é desativado.
     *
     * @param l link
     * @return verdadeiro se o link está pago agora
     */
    private boolean paidMeanwhile(PayLinks.Link l) {
        GatewayPayment old = null;
        try {
            old = gateway.getPayment(l.mpPaymentId());
            if (old.open()) {
                gateway.cancel(l.mpPaymentId());
            }
        } catch (RuntimeException e) {
            LOG.warn("Não consegui conferir o Pix anterior do link {}", l.id(), e);
        }
        if (old == null || !old.approved()) {
            return false;
        }
        if (payments.markPaid(l.id(), l.mpPaymentId(), old.amount()).isPresent() || links.paid(l.id())) {
            return true;
        }
        LOG.warn("Pix anterior {} do link {} aprovado com valor menor que o cobrado", l.mpPaymentId(), l.id());
        return false;
    }

    /**
     * Gera o Pix novo do link.
     *
     * @param l link
     * @return o Pix
     */
    private Map<String, Object> issue(PayLinks.Link l) {
        int attempt = l.pixAttempts() + 1;
        Instant expiresAt = clock.instant().plus(settings.pixValidity());
        try {
            GatewayPayment pay = gateway.createPix(new PaymentGateway.PixCharge(LinkPayments.LINK_REF + l.id(),
                    "rayka-link-" + l.id() + "-" + attempt, l.amount(), "Pedido #" + l.number() + " - Raykookie",
                    settings.payerEmail(), PixUseCases.firstName(l.customer()), expiresAt));
            String base64 = pay.qrBase64() == null ? "" : pay.qrBase64();
            links.savePix(l.id(), pay.id(), pay.qrCode(), base64, expiresAt, attempt);
            return pix(pay.qrCode(), base64, Math.floorDiv(Duration.between(clock.instant(), expiresAt).toMillis(),
                    1000), false);
        } catch (RuntimeException e) {
            LOG.error("Falha ao gerar o Pix do link {}", l.id(), e);
            throw new DomainException("Não conseguimos gerar o Pix agora. Tente de novo em instantes.", 502);
        }
    }

    /**
     * Desativa um Pix pendente; falha não é erro (vence sozinho).
     *
     * @param paymentId pagamento
     */
    private void cancelQuietly(String paymentId) {
        try {
            gateway.cancel(paymentId);
        } catch (RuntimeException e) {
            LOG.debug("Pix {} já vencido ou cancelado", paymentId, e);
        }
    }

    /**
     * Pix para a resposta.
     *
     * @param code      copia e cola
     * @param qrBase64  QR
     * @param expiresIn segundos
     * @param expired   se venceu
     * @return o Pix
     */
    private static Map<String, Object> pix(String code, String qrBase64, long expiresIn, boolean expired) {
        Map<String, Object> pix = new LinkedHashMap<>();
        pix.put("code", code);
        pix.put("qr_base64", qrBase64);
        pix.put("expires_in", expiresIn);
        pix.put("expired", expired);
        return pix;
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
     * Token novo.
     *
     * @return 43 caracteres base64url
     */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Convidado e pedido de um link pago.
     *
     * @param customerId  cliente convidado
     * @param shopOrderId pedido do app
     */
    public record GuestOrder(long customerId, long shopOrderId) {
    }
}
