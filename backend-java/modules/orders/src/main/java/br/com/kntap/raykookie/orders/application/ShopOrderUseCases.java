package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.orders.domain.CouponRules;
import br.com.kntap.raykookie.customers.api.CustomersApi;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import br.com.kntap.raykookie.orders.application.ports.OrderNumbers;
import br.com.kntap.raykookie.orders.application.ports.Coupons;
import br.com.kntap.raykookie.orders.application.ports.ShopOrders;
import br.com.kntap.raykookie.orders.domain.PublicCodes;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.api.StorefrontApi;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pedido da loja feito pelo cliente ({@code POST /api/cliente/pedidos}): grava o pedido e as linhas da tela Pedidos,
 * avisa a dona e inicia a cobrança (Pix ou cartão) depois de gravar.
 */
@Service
public class ShopOrderUseCases {

    /** Loja sem dona cadastrada. */
    static final String SHOP_CLOSED = "A loja não está recebendo pedidos no momento. Tente mais tarde.";

    /** Limite de pedidos (G11). */
    static final String TOO_MANY = "Você fez muitos pedidos em pouco tempo. Tente de novo mais tarde.";

    /** Pedidos pagos online ainda sem pagamento que o cliente pode ter em aberto (G11). */
    static final int MAX_OPEN_UNPAID = 3;

    /** Cartão sem o módulo de pagamentos. */
    static final String CARD_UNAVAILABLE = "Pagamento com cartão indisponível no momento.";

    /** Pedidos da loja. */
    private final ShopOrders shopOrders;

    /** Numeração da tela Pedidos. */
    private final OrderNumbers numbers;

    /** Cardápio e endereço de retirada. */
    private final StorefrontApi storefront;

    /** E-mail do cliente (para o Mercado Pago). */
    private final CustomersApi customers;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /** Limites por cliente. */
    private final RateLimiter limiter;

    /** Avisos. */
    private final ApplicationEventPublisher events;

    /** Transação da gravação (a cobrança fica de fora). */
    private final TransactionTemplate transactions;

    /** Cobrança (módulo de pagamentos). */
    private final ObjectProvider<ChargeStarter> charges;

    /** Relógio. */
    private final Clock clock;

    /** Cupons da loja. */
    private final Coupons coupons;

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria os casos de uso.
     *
     * @param shopOrders   pedidos da loja
     * @param numbers      numeração
     * @param storefront   cardápio e retirada
     * @param customers    clientes
     * @param shopOwner    a dona
     * @param limiter      limites
     * @param events       avisos
     * @param transactions transações
     * @param charges      cobrança
     * @param clock        relógio
     * @param coupons      cupons da loja
     * @param audit        histórico de auditoria
     */
    public ShopOrderUseCases(ShopOrders shopOrders, OrderNumbers numbers, StorefrontApi storefront,
            CustomersApi customers, ShopOwner shopOwner, RateLimiter limiter, ApplicationEventPublisher events,
            TransactionTemplate transactions, ObjectProvider<ChargeStarter> charges, Clock clock, Coupons coupons,
            AuditTrail audit) {

        // ── 1. Pedidos, cardápio e clientes.
        this.shopOrders = shopOrders;
        this.numbers = numbers;
        this.storefront = storefront;
        this.customers = customers;
        this.shopOwner = shopOwner;

        // ── 2. Limites, avisos e cobrança.
        this.limiter = limiter;
        this.events = events;
        this.transactions = transactions;
        this.charges = charges;
        this.clock = clock;
        this.coupons = coupons;
        this.audit = audit;
    }

    /**
     * Cria o pedido.
     *
     * @param customer cliente
     * @param body     corpo JSON
     * @param clientIp IP real do cliente
     * @return {@code id}, {@code code}, {@code pix} e {@code card}, como o Node responde
     */
    public Map<String, Object> create(CustomerId customer, Map<String, Object> body, String clientIp) {

        // ── 1. Confere o pedido com os preços do cardápio do servidor.
        ShopOrderForm form = ShopOrderForm.from(body, LocalDate.now(clock.withZone(StoreClock.ZONE)),
                storefront.currentMenu().items(),
                code -> shopOwner.isPending() ? Optional.empty() : coupons.find(shopOwner.id(), code));
        if (shopOwner.isPending()) {
            throw new DomainException(SHOP_CLOSED, 503);
        }
        OwnerId owner = shopOwner.id();

        // ── 2. Limites por cliente (G11): pedidos sem pagar em aberto e pedidos por hora.
        if (shopOrders.openUnpaidOnline(customer) >= MAX_OPEN_UNPAID
                || !limiter.hit(RateLimits.ORDER_CREATE, String.valueOf(customer.value()))) {
            throw new TooManyRequestsException(TOO_MANY);
        }

        // ── 3. Grava e avisa a dona (o aviso sai depois do commit).
        ChargeStarter.NewOrder order = transactions.execute(status -> record(owner, customer, form));
        events.publishEvent(new OwnerNotice.NewOrder(owner, order.id(), order.code(), form.contactName(),
                form.total(), form.items().size()));

        // ── 4. Cobrança, fora da transação: falha não desfaz o pedido.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", order.id());
        response.put("code", order.code());
        response.put("pix", "pix".equals(form.paymentMethod()) ? pix(order) : null);
        response.put("card", form.card() == null ? null : card(order, form.card(), clientIp));
        return response;
    }

    /**
     * Grava o pedido, na transação: numeração travada por dona (G10), código público, linhas e contato.
     *
     * @param owner    dona
     * @param customer cliente
     * @param form     pedido conferido
     * @return o pedido gravado
     */
    private ChargeStarter.NewOrder record(OwnerId owner, CustomerId customer, ShopOrderForm form) {

        // ── 1. Trava a numeração e congela o endereço de retirada da época.
        numbers.lock(owner);
        String pickup = "retirada".equals(form.fulfillment())
                ? storefront.activePickupAddress(owner).map(PickupAddress::pickupLine).orElse("") : "";
        int code = PublicCodes.allocate(shopOrders::codeTaken);
        long id = shopOrders.insert(new ShopOrders.NewShopOrder(owner, customer, form, code, pickup));

        // ── 2. Uma linha na tela Pedidos por item.
        String where = ShopOrderNotes.where(form, pickup);
        for (int i = 0; i < form.items().size(); i++) {
            shopOrders.insertLine(new ShopOrders.NewLine(owner, numbers.next(owner), id, form.contactName(),
                    form.items().get(i), form.scheduledFor(), ShopOrderNotes.notes(form, code, i == 0), where));
        }

        // ── 3. Auditoria: o pedido e, se houve, o cupom aplicado (o desconto é o do cadastro, calculado aqui).
        auditOrder(owner, id, code, form);

        // ── 4. Guarda telefone e endereço para o próximo pedido.
        shopOrders.rememberContact(customer, form);
        String email = customers.byId(customer).map(CustomersApi.CustomerSummary::email).orElse("");
        return new ChargeStarter.NewOrder(id, code, owner, customer, email, form.contactName(), form.phone(),
                form.total(), form.items().size());
    }

    /**
     * Grava no histórico o pedido do cliente e o cupom aplicado.
     *
     * @param owner dona
     * @param id    pedido
     * @param code  código público
     * @param form  pedido conferido
     */
    private void auditOrder(OwnerId owner, long id, int code, ShopOrderForm form) {

        // ── 1. O pedido: total já com desconto, subtotal e forma de pagamento.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("total", form.total().toDecimal());
        after.put("subtotal", form.subtotal().toDecimal());
        after.put("items", form.items().size());
        after.put("payment_method", form.paymentMethod());
        audit.record(owner, Entry.of("pedido", "criou", "shop_order", id, null, after,
                "Pedido #" + code + " de " + form.contactName() + ": R$ " + AuditValues.num(form.total().toDecimal())
                        + " (" + form.paymentMethod() + ")"));

        // ── 2. O cupom aplicado, se houve (o desconto é o do cadastro, calculado no servidor).
        if (form.coupon() != null && form.coupon().couponId() > 0) {
            CouponRules.Applied coupon = form.coupon();
            audit.record(owner, Entry.of("cupom", "aplicou", "coupon", coupon.couponId(), null,
                    Map.of("code", coupon.code(), "percent", coupon.percent(), "discount",
                            coupon.discount().toDecimal(), "order", id),
                    "Cupom " + coupon.code() + " aplicado no pedido #" + code + ": desconto de R$ "
                            + AuditValues.num(coupon.discount().toDecimal())));
        }
    }

    /**
     * Gera o Pix; sem o módulo de pagamentos (ou se o Mercado Pago falhar), o pedido fica pendente.
     *
     * @param order pedido
     * @return o copia e cola, ou nulo
     */
    private Map<String, Object> pix(ChargeStarter.NewOrder order) {
        ChargeStarter starter = charges.getIfAvailable();
        return starter == null ? null : starter.startPix(order);
    }

    /**
     * Cobra o cartão.
     *
     * @param order    pedido
     * @param card     dados do cartão
     * @param clientIp IP real do cliente
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    private Map<String, Object> card(ChargeStarter.NewOrder order, ShopOrderForm.Card card, String clientIp) {
        ChargeStarter starter = charges.getIfAvailable();
        if (starter == null) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("approved", false);
            refused.put("reason", CARD_UNAVAILABLE);
            return refused;
        }
        return starter.startCard(order, new ChargeStarter.Card(card.token(), card.methodId(), card.issuerId(),
                card.cpf(), card.installments(), card.deviceId(), card.typeId()), clientIp);
    }
}
