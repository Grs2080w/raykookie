package br.com.kntap.raykookie.audit.adapter.in.web;

import br.com.kntap.raykookie.audit.JdbcAuditReader;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Histórico de auditoria para o app de gestão: {@code GET /api/historico}. Só leitura e só para a dona: não existe
 * rota para editar nem apagar o histórico (nem para quem é administradora).
 */
@RestController
public class AuditController {

    /** Leitor do histórico. */
    private final JdbcAuditReader reader;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param reader leitor do histórico
     * @param actor  quem está chamando
     */
    public AuditController(JdbcAuditReader reader, CurrentActor actor) {
        this.reader = reader;
        this.actor = actor;
    }

    /**
     * Uma página do histórico, dos mais recentes aos mais antigos.
     *
     * @param page     página, a partir de 1
     * @param perPage  linhas por página (até 100)
     * @param from     a partir de (ISO-8601)
     * @param to       até (ISO-8601, exclusive)
     * @param category categoria
     * @param action   ação
     * @param user     usuário (parte do nome ou id)
     * @param q        busca livre
     * @return {@code items}, {@code total}, {@code page}, {@code per_page} e {@code filters}
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/historico")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "per_page", defaultValue = "30") int perPage,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String category, @RequestParam(required = false) String action,
            @RequestParam(required = false) String user, @RequestParam(required = false) String q) {
        return reader.page(actor.requireOwner(), new JdbcAuditReader.Filter(from, to, category, action, user, q), page,
                perPage);
    }
}
