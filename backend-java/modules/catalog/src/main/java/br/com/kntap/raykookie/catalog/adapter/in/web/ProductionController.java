package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.ProductionUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estoque de produção da dona: {@code /api/production}.
 */
@RestController
public class ProductionController {

    /** Casos de uso. */
    private final ProductionUseCases production;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param production casos de uso
     * @param actor      quem está chamando
     */
    public ProductionController(ProductionUseCases production, CurrentActor actor) {
        this.production = production;
        this.actor = actor;
    }

    /**
     * Unidades prontas de cada receita, custo, preço, lucro e quantas unidades o estoque de ingredientes cobre.
     *
     * @return {@code recipes} e {@code summary}
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/production/stock")
    public Map<String, Object> stock() {
        return production.overview(actor.requireOwner());
    }

    /**
     * Corrige à mão as unidades prontas de uma receita.
     *
     * @param recipe receita
     * @param body   {@code qty}
     * @return a linha da receita
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/production/stock/{id}")
    public Map<String, Object> adjust(@PathVariable("id") long recipe, @RequestBody Map<String, Object> body) {
        return production.adjust(actor.requireOwner(), recipe, body);
    }

    /**
     * Prévia de uma produção, sem gravar.
     *
     * @param body {@code recipe_id} e {@code qty}
     * @return o que seria consumido
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/production/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        return production.preview(actor.requireOwner(), body);
    }

    /**
     * Registra uma produção.
     *
     * @param body {@code recipe_id}, {@code qty} e {@code force}
     * @return a produção registrada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/production/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return production.register(actor.requireOwner(), body);
    }

    /**
     * Histórico de produção.
     *
     * @param recipe filtro por receita
     * @param from   dia inicial
     * @param to     dia final
     * @return as produções
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/production/runs")
    public List<Map<String, Object>> runs(@RequestParam(name = "recipe", required = false) String recipe,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return production.runs(actor.requireOwner(), recipe, from, to);
    }

    /**
     * Desfaz uma produção.
     *
     * @param id produção
     * @return a produção, já desfeita
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/production/runs/{id}/undo")
    public Map<String, Object> undo(@PathVariable("id") long id) {
        return production.undo(actor.requireOwner(), id);
    }
}
