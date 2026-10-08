package br.com.kntap.raykookie.purchasing.adapter.in.web;

import br.com.kntap.raykookie.purchasing.application.SupplierUseCases;
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
 * Fornecedores: {@code /api/suppliers}.
 */
@RestController
public class SuppliersController {

    /** Casos de uso. */
    private final SupplierUseCases suppliers;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param suppliers casos de uso
     * @param actor     quem está chamando
     */
    public SuppliersController(SupplierUseCases suppliers, CurrentActor actor) {
        this.suppliers = suppliers;
        this.actor = actor;
    }

    /**
     * Fornecedores por nome.
     *
     * @return os fornecedores
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/suppliers")
    public List<Map<String, Object>> list() {
        return suppliers.list(actor.requireOwner());
    }

    /**
     * Cria um fornecedor.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        return suppliers.save(actor.requireOwner(), null, body == null ? Map.of() : body);
    }

    /**
     * Edita um fornecedor.
     *
     * @param id   fornecedor
     * @param body dados
     * @return a linha editada
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/suppliers/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return suppliers.save(actor.requireOwner(), id, body == null ? Map.of() : body);
    }

    /**
     * Apaga um fornecedor.
     *
     * @param id fornecedor
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/suppliers/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        suppliers.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }
}
