package br.com.kntap.raykookie.audit.adapter.in.web;

import br.com.kntap.raykookie.audit.application.PixPresencialAudit;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Eventos da Venda Presencial informados pelo app de gestão: {@code POST /api/historico/pix-presencial}. O Pix estático
 * nasce no aparelho (sem internet); o app manda a fila quando volta a ter rede.
 */
@RestController
public class PixPresencialController {

    /** Casos de uso. */
    private final PixPresencialAudit pix;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param pix   casos de uso
     * @param actor quem está chamando
     */
    public PixPresencialController(PixPresencialAudit pix, CurrentActor actor) {
        this.pix = pix;
        this.actor = actor;
    }

    /**
     * Grava os eventos da fila do aparelho.
     *
     * @param body {@code {"events":[{"kind":"qr_gerado","title":"…","amount":12.5,"at":"…"}]}}
     * @return quantos foram gravados
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/historico/pix-presencial")
    public Map<String, Object> record(@RequestBody(required = false) Map<String, Object> body) {
        List<?> events = body != null && body.get("events") instanceof List<?> list ? list : null;
        return Map.of("ok", true, "saved", pix.record(actor.requireOwner(), events));
    }
}
