package br.com.kntap.raykookie.purchasing.adapter.in.web;

import br.com.kntap.raykookie.purchasing.application.PriceUseCases;
import br.com.kntap.raykookie.purchasing.application.SearchUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cotações e busca de preços: {@code /api/prices/*} e {@code /api/ingredients/{id}/prices|use-price}.
 */
@RestController
public class PricesController {

    /** Cotações. */
    private final PriceUseCases prices;

    /** Busca nos sites. */
    private final SearchUseCases search;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param prices cotações
     * @param search busca nos sites
     * @param actor  quem está chamando
     */
    public PricesController(PriceUseCases prices, SearchUseCases search, CurrentActor actor) {
        this.prices = prices;
        this.search = search;
        this.actor = actor;
    }

    /**
     * Corpo ausente vira vazio (como o {@code req.json().catch(() => ({}))} do Node).
     *
     * @param body corpo
     * @return o corpo ou vazio
     */
    private static Map<String, Object> orEmpty(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    /**
     * Liga um produto do site ao ingrediente.
     *
     * @param body {@code ingredient_id}, {@code supplier_id}, {@code name}, {@code url}, {@code price},
     *             {@code size}, {@code unit}
     * @return {@code {"ok":true,"quote_id":"…"}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/prices/link")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> link(@RequestBody(required = false) Map<String, Object> body) {
        return prices.link(actor.requireOwner(), orEmpty(body));
    }

    /**
     * Cotação manual.
     *
     * @param body {@code ingredient_id}, {@code supplier_id}, {@code price}, {@code pack_size}
     * @return {@code {"ok":true,"quote_id":"…"}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/prices/quote")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> quote(@RequestBody(required = false) Map<String, Object> body) {
        return prices.quote(actor.requireOwner(), orEmpty(body));
    }

    /**
     * Procura o ingrediente nos sites dos fornecedores.
     *
     * @param body {@code ingredient_id} e {@code term}
     * @return resultados e erros por site
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/prices/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        return search.search(actor.requireOwner(), orEmpty(body));
    }

    /**
     * Cotações e histórico de um ingrediente.
     *
     * @param id ingrediente
     * @return ingrediente, custo atual, cotações e histórico
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/ingredients/{id}/prices")
    public Map<String, Object> ingredientPrices(@PathVariable long id) {
        return prices.ingredientPrices(actor.requireOwner(), id);
    }

    /**
     * "Usar este preço".
     *
     * @param id   ingrediente
     * @param body {@code quote_id}
     * @return {@code {"ok":true,"alerts":n}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/ingredients/{id}/use-price")
    public Map<String, Object> usePrice(@PathVariable long id, @RequestBody(required = false) Map<String,
            Object> body) {
        return prices.useQuote(actor.requireOwner(), id, orEmpty(body));
    }
}
