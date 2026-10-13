package br.com.kntap.raykookie.engagement.adapter.in.web;

import br.com.kntap.raykookie.engagement.application.EngagementUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Engajamento: o site e os apps enviam eventos anônimos (público) e a dona lê os números.
 */
@RestController
public class EngagementController {

    /** Casos de uso. */
    private final EngagementUseCases engagement;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** IP real do cliente (só conta no limite de envios). */
    private final ClientIpResolver clientIp;

    /**
     * Cria o controller.
     *
     * @param engagement casos de uso
     * @param actor      quem está chamando
     * @param clientIp   IP real do cliente
     */
    public EngagementController(EngagementUseCases engagement, CurrentActor actor, ClientIpResolver clientIp) {
        this.engagement = engagement;
        this.actor = actor;
        this.clientIp = clientIp;
    }

    /**
     * Recebe um lote de eventos. O caminho é neutro de propósito (listas de bloqueio de anúncios miram "analytics").
     *
     * @param body    {@code session_id}, {@code platform}, {@code app_version} e {@code events}
     * @param request requisição (só para o IP do limite)
     * @return 204, sempre que o formato do lote é válido
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/medicao/eventos")
    public ResponseEntity<Void> ingest(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        engagement.ingest(body, clientIp.resolve(request));
        return ResponseEntity.noContent().build();
    }

    /**
     * Resumo do período.
     *
     * @param from     primeiro dia ({@code AAAA-MM-DD})
     * @param to       último dia
     * @param platform {@code site}, {@code android} ou {@code ios}
     * @return o resumo
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/engagement/overview")
    public Map<String, Object> overview(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String platform) {
        return engagement.overview(actor.requireOwner(), from, to, platform);
    }

    /**
     * Funil do pedido.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma
     * @return os passos
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/engagement/funnel")
    public Map<String, Object> funnel(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String platform) {
        return engagement.funnel(actor.requireOwner(), from, to, platform);
    }

    /**
     * Receitas e combos.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma
     * @param sort     coluna de ordenação
     * @return a lista
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/engagement/recipes")
    public Map<String, Object> recipes(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String platform,
            @RequestParam(required = false) String sort) {
        return engagement.recipes(actor.requireOwner(), from, to, platform, sort);
    }

    /**
     * Páginas e telas.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma
     * @return a lista
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/engagement/pages")
    public Map<String, Object> pages(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String platform) {
        return engagement.pages(actor.requireOwner(), from, to, platform);
    }

    /**
     * Origens e plataformas.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma
     * @return origens e plataformas
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/engagement/sources")
    public Map<String, Object> sources(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String platform) {
        return engagement.sources(actor.requireOwner(), from, to, platform);
    }
}
