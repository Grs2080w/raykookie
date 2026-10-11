package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.ComboUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Combos do cardápio (seção Combos da página do Cardápio). Toda rota devolve a página inteira atualizada.
 */
@RestController
public class CombosController {

    /** Casos de uso. */
    private final ComboUseCases combos;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param combos casos de uso
     * @param actor  quem está chamando
     */
    public CombosController(ComboUseCases combos, CurrentActor actor) {
        this.combos = combos;
        this.actor = actor;
    }

    /**
     * Produtos, desconto sugerido e combos calculados.
     *
     * @return a página
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/combos")
    public Map<String, Object> page() {
        return combos.page(actor.requireOwner());
    }

    /**
     * Novo combo.
     *
     * @param body {@code name}, {@code price}, {@code show_on_menu} e {@code items}
     * @return a página
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/combos")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return combos.save(actor.requireOwner(), null, body);
    }

    /**
     * Edita um combo.
     *
     * @param id   combo
     * @param body dados
     * @return a página
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/combos/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return combos.save(actor.requireOwner(), id, body);
    }

    /**
     * Apaga um combo.
     *
     * @param id combo
     * @return a página
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/combos/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        return combos.delete(actor.requireOwner(), id);
    }

    /**
     * Desconto sugerido dos combos.
     *
     * @param body {@code discount_pct}
     * @return a página
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/combos/settings")
    public Map<String, Object> settings(@RequestBody Map<String, Object> body) {
        return combos.setDiscount(actor.requireOwner(), body);
    }
}
