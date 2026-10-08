package br.com.kntap.raykookie.refunds.adapter.in.web;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.payments.api.PaymentsApi;
import br.com.kntap.raykookie.refunds.application.CustomerRefundUseCases;
import br.com.kntap.raykookie.refunds.application.OwnerRefundUseCases;
import br.com.kntap.raykookie.refunds.application.RefundViews;
import br.com.kntap.raykookie.refunds.application.ports.RefundQueries;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.NodeInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Devoluções: as telas da dona, o pedido e a desistência do cliente (pelo app ou pelo link de pagamento) e as
 * fotos.
 */
@RestController
public class RefundsController {

    /** Pedido inexistente. */
    static final String ORDER_NOT_FOUND = "Pedido não encontrado.";

    /** Link inexistente. */
    static final String LINK_NOT_FOUND = "Link não encontrado.";

    /** Telas. */
    private final RefundViews views;

    /** O que a dona faz. */
    private final OwnerRefundUseCases owner;

    /** O que o cliente faz. */
    private final CustomerRefundUseCases customer;

    /** Convidado do link. */
    private final PaymentsApi payments;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param views    telas
     * @param owner    o que a dona faz
     * @param customer o que o cliente faz
     * @param payments convidado do link
     * @param actor    quem está chamando
     */
    public RefundsController(RefundViews views, OwnerRefundUseCases owner, CustomerRefundUseCases customer,
            PaymentsApi payments, CurrentActor actor) {
        this.views = views;
        this.owner = owner;
        this.customer = customer;
        this.payments = payments;
        this.actor = actor;
    }

    /**
     * Lista da dona.
     *
     * @param status grupo ({@code aguardando}, {@code andamento}, {@code concluidas}, {@code negadas})
     * @return a lista, as contagens e o valor em andamento
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/devolucoes")
    public Map<String, Object> list(@RequestParam(required = false) String status) {
        return views.list(actor.requireOwner(), status);
    }

    /**
     * Uma devolução.
     *
     * @param id devolução
     * @return o detalhe
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/devolucoes/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return views.detail(id, actor.requireOwner());
    }

    /**
     * Aprovar (total ou parcial) ou negar.
     *
     * @param id   devolução
     * @param body {@code approve}, {@code amount}, {@code code} e {@code note}
     * @return {@code ok} e {@code status}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/devolucoes/{id}/decisao")
    public Map<String, Object> decide(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return owner.decide(id, actor.requireOwner(), Boolean.TRUE.equals(b.get("approve")), b.get("amount"),
                b.get("code"), b.get("note"));
    }

    /**
     * Refaz uma devolução que falhou.
     *
     * @param id devolução
     * @return {@code ok} e {@code status}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/devolucoes/{id}/tentar")
    public Map<String, Object> retry(@PathVariable long id) {
        return owner.retry(id, actor.requireOwner());
    }

    /**
     * Foto de uma devolução da dona.
     *
     * @param id devolução
     * @param n  índice
     * @return a imagem
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/devolucoes/{id}/fotos/{n}")
    public ResponseEntity<byte[]> ownerPhoto(@PathVariable long id, @PathVariable String n) {
        return photo(NodeInput.integer(n).flatMap(i -> views.ownerPhoto(id, actor.requireOwner(), i.intValue())));
    }

    /**
     * Foto do próprio cliente.
     *
     * @param rid devolução
     * @param n   índice
     * @return a imagem
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/devolucoes/{rid}/fotos/{n}")
    public ResponseEntity<byte[]> customerPhoto(@PathVariable long rid, @PathVariable String n) {
        return photo(NodeInput.integer(n).flatMap(i -> views.customerPhoto(rid, actor.requireCustomer(),
                i.intValue())));
    }

    /**
     * A dona devolve um valor sem cancelar.
     *
     * @param id   pedido da loja
     * @param body {@code amount} e {@code note}
     * @return {@code ok}, {@code refundId} e {@code refunded}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/orders/shop/{id}/devolver")
    public Map<String, Object> ownerRefund(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return owner.ownerRefund(id, actor.requireOwner(), b.get("amount"), b.get("note"));
    }

    /**
     * O cliente pede devolução (multipart).
     *
     * @param id      pedido
     * @param request requisição
     * @return {@code ok} e {@code id}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/pedidos/{id}/devolucao")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> request(@PathVariable String id, HttpServletRequest request) {
        long orderId = NodeInput.integer(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        return customer.request(orderId, actor.requireCustomer(), RefundFormReader.read(request));
    }

    /**
     * O cliente desiste da solicitação.
     *
     * @param id pedido
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.CUSTOMER)
    @DeleteMapping("/api/cliente/pedidos/{id}/devolucao")
    public Map<String, Object> withdraw(@PathVariable String id) {
        long orderId = NodeInput.integer(id).orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND));
        return customer.withdraw(orderId, actor.requireCustomer());
    }

    /**
     * Pedir devolução pelo link de pagamento.
     *
     * @param token   token do link
     * @param request requisição
     * @return {@code ok} e {@code id}
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @PostMapping("/api/pagar/{token}/devolucao")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestByLink(@PathVariable String token, HttpServletRequest request) {
        PaymentsApi.LinkGuest g = guest(token);
        return customer.request(g.shopOrderId(), g.customer(), RefundFormReader.read(request));
    }

    /**
     * Desistir pelo link de pagamento.
     *
     * @param token token do link
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.PAY_LINK_TOKEN)
    @DeleteMapping("/api/pagar/{token}/devolucao")
    public Map<String, Object> withdrawByLink(@PathVariable String token) {
        PaymentsApi.LinkGuest g = guest(token);
        return customer.withdraw(g.shopOrderId(), g.customer());
    }

    /**
     * Convidado de um link pago.
     *
     * @param token token
     * @return o convidado
     */
    private PaymentsApi.LinkGuest guest(String token) {
        return payments.linkGuest(token).orElseThrow(() -> new NotFoundException(LINK_NOT_FOUND));
    }

    /**
     * Resposta da foto (404 sem corpo, como o Node).
     *
     * @param photo foto
     * @return a resposta
     */
    private static ResponseEntity<byte[]> photo(Optional<RefundQueries.Photo> photo) {
        return photo.map(p -> ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, p.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").header("X-Content-Type-Options", "nosniff")
                .body(p.data())).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
