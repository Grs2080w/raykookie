package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.TooManyRequestsException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.Coupons;
import br.com.kntap.raykookie.orders.domain.CouponRules;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.ratelimit.RateLimiter;
import br.com.kntap.raykookie.ratelimit.RateLimits;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.storefront.api.StorefrontApi;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Cupons: a aba Cupons da dona (criar, editar, ativar/desativar, excluir) e a conferência do cliente no último
 * passo do pedido (com o total recalculado pelo cardápio do servidor).
 */
@Service
public class CouponUseCases {

    /** Cupom que não é da dona. */
    static final String NOT_FOUND = "Cupom não encontrado.";

    /** Letras e números sem os que se confundem (0/O, 1/I/L). */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Sorteio dos códigos. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Cupons. */
    private final Coupons coupons;

    /** Cardápio. */
    private final StorefrontApi storefront;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /** Limites. */
    private final RateLimiter limiter;

    /**
     * Cria os casos de uso.
     *
     * @param coupons    cupons
     * @param storefront cardápio
     * @param shopOwner  a dona
     * @param limiter    limites
     */
    public CouponUseCases(Coupons coupons, StorefrontApi storefront, ShopOwner shopOwner, RateLimiter limiter) {
        this.coupons = coupons;
        this.storefront = storefront;
        this.shopOwner = shopOwner;
        this.limiter = limiter;
    }

    /**
     * Cupons da dona.
     *
     * @param owner dona
     * @return os cupons, com quantas vezes foram usados
     */
    public List<Map<String, Object>> list(OwnerId owner) {
        return coupons.list(owner);
    }

    /**
     * Cria um cupom; sem código, o sistema gera um ({@code RAY-XXXXX}).
     *
     * @param owner dona
     * @param body  {@code code} (opcional), {@code percent}, {@code min_total} (opcional) e {@code min_inclusive}
     * @return os cupons
     * @throws ConflictException se o código já existe
     */
    public List<Map<String, Object>> create(OwnerId owner, Map<String, Object> body) {

        // ── 1. Código: o escolhido (conferido) ou um gerado que ainda não existe.
        String code = CouponRules.normalize(NodeInput.text(body.get("code")));
        if (code.isEmpty()) {
            code = generate(owner);
        } else if (coupons.codeTaken(owner, CouponRules.requireCode(code))) {
            throw new ConflictException("Já existe um cupom com esse código.");
        }

        // ── 2. Grava.
        coupons.create(owner, form(code, body, true));
        return coupons.list(owner);
    }

    /**
     * Edita porcentagem, valor mínimo e se está ativo (o código não muda).
     *
     * @param owner dona
     * @param id    cupom
     * @param body  {@code percent}, {@code min_total}, {@code min_inclusive} e {@code active}
     * @return os cupons
     * @throws NotFoundException se o cupom não for da dona
     */
    public List<Map<String, Object>> update(OwnerId owner, long id, Map<String, Object> body) {
        boolean active = !(body.get("active") instanceof Boolean flag) || flag;
        if (!coupons.update(owner, id, form("", body, active))) {
            throw new NotFoundException(NOT_FOUND);
        }
        return coupons.list(owner);
    }

    /**
     * Exclui um cupom que nunca foi usado (usado, só dá para desativar).
     *
     * @param owner dona
     * @param id    cupom
     * @return os cupons
     * @throws ConflictException se já foi usado
     */
    public List<Map<String, Object>> delete(OwnerId owner, long id) {
        if (coupons.uses(owner, id) > 0) {
            throw new ConflictException("Este cupom já foi usado em pedidos. Desative em vez de excluir.");
        }
        coupons.delete(owner, id);
        return coupons.list(owner);
    }

    /**
     * Confere o cupom no último passo do pedido: o total vem do cardápio do servidor, nunca do app.
     *
     * @param customer cliente
     * @param body     {@code code} e {@code items} (como no pedido)
     * @return {@code code}, {@code percent}, {@code subtotal}, {@code discount} e {@code total}
     * @throws InvalidInputException com a mensagem para o cliente (cupom inválido, valor mínimo…)
     * @throws TooManyRequestsException se o cliente conferiu códigos demais em pouco tempo
     */
    public Map<String, Object> check(CustomerId customer, Map<String, Object> body) {

        // ── 1. Limite de tentativas (contra chutar códigos).
        if (!limiter.hit(RateLimits.COUPON_CHECK, String.valueOf(customer.value()))) {
            throw new TooManyRequestsException("Muitas tentativas de cupom. Espere alguns minutos.");
        }

        // ── 2. Subtotal pelo cardápio e o cupom da loja.
        List<ShopOrderForm.Line> lines = ShopOrderForm.cart(body.get("items"), storefront.currentMenu().items());
        Money subtotal = ShopOrderForm.subtotal(lines);
        String code = CouponRules.normalize(NodeInput.text(body.get("code")));
        Optional<CouponRules.Coupon> coupon = code.isEmpty() || shopOwner.isPending() ? Optional.empty()
                : coupons.find(shopOwner.id(), code);
        CouponRules.Applied applied = CouponRules.apply(coupon.orElse(null), subtotal);

        // ── 3. O desconto e o total com desconto, para o app mostrar antes de finalizar o pedido.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", applied.code());
        result.put("percent", NodeRows.number(applied.percent()));
        result.put("subtotal", NodeRows.number(subtotal.toDecimal()));
        result.put("discount", NodeRows.number(applied.discount().toDecimal()));
        result.put("total", NodeRows.number(subtotal.minus(applied.discount()).toDecimal()));
        return result;
    }

    /**
     * Lê porcentagem e valor mínimo do corpo.
     *
     * @param code   código
     * @param body   corpo
     * @param active se fica ativo
     * @return os dados
     * @throws InvalidInputException fora das faixas
     */
    private static Coupons.Form form(String code, Map<String, Object> body, boolean active) {

        // ── 1. Porcentagem de 1% a 90%.
        BigDecimal percent = BigDecimal.valueOf(NodeInput.number(body.get("percent"))).setScale(2,
                RoundingMode.HALF_UP);
        if (percent.compareTo(BigDecimal.ONE) < 0 || percent.compareTo(CouponRules.MAX_PERCENT) > 0) {
            throw new InvalidInputException("O desconto do cupom vai de 1% a 90%.");
        }

        // ── 2. Valor mínimo opcional (vazio ou zero = nenhum).
        double min = NodeInput.number(body.get("min_total"));
        BigDecimal minTotal = min > 0 ? BigDecimal.valueOf(min).setScale(2, RoundingMode.HALF_UP) : null;
        boolean inclusive = !(body.get("min_inclusive") instanceof Boolean flag) || flag;
        return new Coupons.Form(code, percent, minTotal, inclusive, active);
    }

    /**
     * Código novo que ainda não existe na loja.
     *
     * @param owner dona
     * @return o código
     */
    private String generate(OwnerId owner) {
        String code;
        do {
            StringBuilder b = new StringBuilder("RAY-");
            for (int i = 0; i < 5; i++) {
                b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            code = b.toString();
        } while (coupons.codeTaken(owner, code));
        return code;
    }
}
