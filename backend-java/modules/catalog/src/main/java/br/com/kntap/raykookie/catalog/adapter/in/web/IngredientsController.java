package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.IngredientUseCases;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingredientes do estoque da dona: {@code /api/ingredients}.
 */
@RestController
public class IngredientsController {

    /** Casos de uso. */
    private final IngredientUseCases ingredients;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param ingredients casos de uso
     * @param actor       quem está chamando
     */
    public IngredientsController(IngredientUseCases ingredients, CurrentActor actor) {
        this.ingredients = ingredients;
        this.actor = actor;
    }

    /**
     * Lista os ingredientes.
     *
     * @return os ingredientes, por nome
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/ingredients")
    public List<Map<String, Object>> list() {
        return ingredients.list(actor.requireOwner());
    }

    /**
     * Cria um ingrediente.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/ingredients")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return ingredients.create(actor.requireOwner(), body);
    }

    /**
     * Edita um ingrediente (ou só o estoque).
     *
     * @param id   ingrediente
     * @param body dados
     * @return a linha editada
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/ingredients/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ingredients.update(actor.requireOwner(), id, body);
    }

    /**
     * Apaga um ingrediente que nenhuma receita usa.
     *
     * @param id ingrediente
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/ingredients/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        ingredients.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }
}
