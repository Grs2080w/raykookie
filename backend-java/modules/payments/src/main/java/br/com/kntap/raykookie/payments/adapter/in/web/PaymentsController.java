package br.com.kntap.raykookie.payments.adapter.in.web;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.orders.api.CardFields;
import br.com.kntap.raykookie.payments.application.OrderPaymentUseCases;
import br.com.kntap.raykookie.payments.application.PayLinkUseCases;
import br.com.kntap.raykookie.payments.application.PixUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.ClientIpResolver;
import br.com.kntap.raykookie.web.NodeInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pagamentos: Pix novo e outro cartão do pedido do app, link de pagamento da dona e a página pública do link.
 */
@RestController
public class PaymentsController {

    /** Pedido inexistente. */
    static final String ORDER_NOT_FOUND = "Pedido não encontrado.";

    /** Pix do pedido. */
    private final PixUseCases pix;

    /** Cartão do pedido. */
    private final OrderPaymentUseCases orders;

    /** Link de pagamento. */
    private final PayLinkUseCases links;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** IP real. */
    private final ClientIpResolver clientIp;

    /**
     * Cria o controller.
     *
     * @param pix      Pix do pedido
     * @param orders   cartão do pedido
     * @param links    link de pagamento
     * @param actor    quem está chamando
     * @param clientIp IP real
     */
    public PaymentsController(PixUseCases pix, OrderPaymentUseCases orders, PayLinkUseCases links, CurrentActor actor,
            ClientIpResolver clientIp) {
        this.pix = pix;
        this.orders = orders;
        this.links = links;
        this.actor = actor;
        this.clientIp = clientIp;
    }

    /**
     * "Gerar novo Pix".
     *
     * @param id pedido
     * @return o estado e o Pix
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/pedidos/{id}/pix")
    public Map<String, Object> regeneratePix(@PathVariable String id) {
        return pix.regenerate(orderId(id), actor.requireCustomer());
    }

    /**
     * Situação do pagamento com cartão do pedido, sem cobrar de novo: o cliente chama depois de concluir o desafio 3DS
     * ou enquanto o cartão está em análise.
     *
     * @param id id do pedido
     * @return {@code approved}, {@code pending} (com {@code challenge_url}) ou a recusa com {@code reason}
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/pedidos/{id}/cartao")
    public Map<String, Object> cardStatus(@PathVariable String id) {
        return orders.cardStatus(orderId(id), actor.requireCustomer());
    }

    /**
     * "Tentar outro cartão".
     *
     * @param id      pedido
     * @param body    cartão
     * @param request requisição (IP real)
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/pedidos/{id}/cartao")
    public Map<String, Object> retryCard(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        long orderId = orderId(id);
        return orders.retryCard(orderId, actor.requireCustomer(), CardFields.parse(orEmpty(body)),
                clientIp.resolve(request));
    }

    /**
     * Link de pagamento de um pedido registrado à mão ({@code amount} opcional, em pt-BR ou número).
     *
     * @param id   linha
     * @param body {@code amount}
     * @return {@code url} e {@code amount}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/orders/{id}/pagamento")
    public Map<String, Object> payLink(@PathVariable String id, @RequestBody(required = false) Map<String,
            Object> body) {
        long orderId = orderId(id);
        Object raw = orEmpty(body).get("amount");
        Money amount = raw == null || NodeInput.text(raw).isEmpty() ? null
                : Money.ofCents(Math.round(NodeInput.number(raw) * 100));
        return links.ensure(actor.requireOwner(), orderId, amount);
    }

    /**
     * Página pública do link (só leitura).
     *
     * @param token token
     * @return a página
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @GetMapping("/api/pagar/{token}")
    public Map<String, Object> view(@PathVariable String token) {
        return links.view(token);
    }

    /**
     * "Pagar com Pix" do link.
     *
     * @param token token
     * @return o estado e o Pix
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @PostMapping("/api/pagar/{token}/pix")
    public Map<String, Object> linkPix(@PathVariable String token) {
        return links.pix(token);
    }

    /**
     * Situação do pagamento com cartão do link, sem cobrar de novo (depois do desafio 3DS ou da análise).
     *
     * @param token token do link
     * @return {@code approved}, {@code pending} (com {@code challenge_url}) ou a recusa com {@code reason}
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @GetMapping("/api/pagar/{token}/cartao")
    public Map<String, Object> linkCardStatus(@PathVariable String token) {
        return links.cardStatus(token);
    }

    /**
     * "Pagar com cartão" do link.
     *
     * @param token   token
     * @param body    cartão
     * @param request requisição (IP real)
     * @return {@code approved}, {@code pending} e {@code reason}
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @PostMapping("/api/pagar/{token}/cartao")
    public Map<String, Object> linkCard(@PathVariable String token,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        return links.card(token, CardFields.parse(orEmpty(body)), clientIp.resolve(request));
    }

    /**
     * O cliente do link cancela o pedido pago e ainda não iniciado.
     *
     * @param token token
     * @param body  {@code reason_code} e {@code reason}
     * @return o resultado do cancelamento
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @PostMapping("/api/pagar/{token}/cancelar")
    public Map<String, Object> linkCancel(@PathVariable String token,
            @RequestBody(required = false) Map<String, Object> body) {
        return links.cancel(token, orEmpty(body));
    }

    /**
     * Id inteiro do caminho ({@code Number.isInteger(Number(id))}).
     *
     * @param id texto
     * @return o id
     */
    static long orderId(String id) {
        return NodeInput.integer(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
    }

    /**
     * Corpo ausente vira vazio.
     *
     * @param body corpo
     * @return o corpo ou vazio
     */
    static Map<String, Object> orEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }
}
