package br.com.kntap.raykookie.purchasing.adapter.in.web;

import br.com.kntap.raykookie.purchasing.application.ReceiptUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nota fiscal: {@code /api/nota/ler} e {@code /api/nota/importar}.
 */
@RestController
public class NotaController {

    /** Casos de uso. */
    private final ReceiptUseCases receipts;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param receipts casos de uso
     * @param actor    quem está chamando
     */
    public NotaController(ReceiptUseCases receipts, CurrentActor actor) {
        this.receipts = receipts;
        this.actor = actor;
    }

    /**
     * Lê a página da nota que o app baixou.
     *
     * @param body {@code url} e {@code html}
     * @return a nota com as sugestões
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/nota/ler")
    public Map<String, Object> read(@RequestBody Map<String, Object> body) {
        return receipts.read(actor.requireOwner(), body);
    }

    /**
     * Lança a nota no estoque.
     *
     * @param body {@code key}, {@code store}, {@code date}, {@code total} e {@code items}
     * @return {@code {"added":n,"created":n}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/nota/importar")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> importReceipt(@RequestBody Map<String, Object> body) {
        return receipts.importReceipt(actor.requireOwner(), body);
    }
}
