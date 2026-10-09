package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.SetupUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categorias, gastos fixos e ajustes: {@code /api/categories}, {@code /api/fixed-costs} e {@code /api/settings}.
 */
@RestController
public class SetupController {

    /** Resposta de sucesso sem dados. */
    private static final Map<String, Object> OK = Map.of("ok", true);

    /** Casos de uso. */
    private final SetupUseCases setup;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param setup casos de uso
     * @param actor quem está chamando
     */
    public SetupController(SetupUseCases setup, CurrentActor actor) {
        this.setup = setup;
        this.actor = actor;
    }

    /**
     * Categorias, opcionalmente de um tipo.
     *
     * @param kind {@code ingredient}, {@code recipe} ou {@code expense}
     * @return as categorias com a contagem de usos
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/categories")
    public List<Map<String, Object>> categories(@RequestParam(value = "kind", required = false) String kind) {
        return setup.categories(actor.requireOwner(), kind);
    }

    /**
     * Cria uma categoria.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@RequestBody Map<String, Object> body) {
        return setup.createCategory(actor.requireOwner(), body);
    }

    /**
     * Edita uma categoria.
     *
     * @param id   categoria
     * @param body dados
     * @return a linha editada
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/categories/{id}")
    public Map<String, Object> updateCategory(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return setup.updateCategory(actor.requireOwner(), id, body);
    }

    /**
     * Apaga uma categoria.
     *
     * @param id categoria
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/categories/{id}")
    public Map<String, Object> deleteCategory(@PathVariable long id) {
        setup.deleteCategory(actor.requireOwner(), id);
        return OK;
    }

    /**
     * Gastos fixos.
     *
     * @return os gastos
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/fixed-costs")
    public List<Map<String, Object>> fixedCosts() {
        return setup.fixedCosts(actor.requireOwner());
    }

    /**
     * Cria um gasto.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/fixed-costs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFixedCost(@RequestBody Map<String, Object> body) {
        return setup.createFixedCost(actor.requireOwner(), body);
    }

    /**
     * Edita um gasto.
     *
     * @param id   gasto
     * @param body dados
     * @return a linha editada
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/fixed-costs/{id}")
    public Map<String, Object> updateFixedCost(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return setup.updateFixedCost(actor.requireOwner(), id, body);
    }

    /**
     * Apaga um gasto.
     *
     * @param id gasto
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/fixed-costs/{id}")
    public Map<String, Object> deleteFixedCost(@PathVariable long id) {
        setup.deleteFixedCost(actor.requireOwner(), id);
        return OK;
    }

    /**
     * Ajustes da dona.
     *
     * @return os ajustes
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/settings")
    public Map<String, Object> settings() {
        return setup.settings(actor.requireOwner());
    }

    /**
     * Grava os ajustes.
     *
     * @param body dados
     * @return a linha gravada
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body) {
        return setup.saveSettings(actor.requireOwner(), body);
    }
}
