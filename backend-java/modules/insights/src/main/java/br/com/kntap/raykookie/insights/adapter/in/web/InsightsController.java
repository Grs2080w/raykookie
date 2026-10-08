package br.com.kntap.raykookie.insights.adapter.in.web;

import br.com.kntap.raykookie.insights.application.InsightsUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Painel da dona ({@code /api/dashboard}) e metas do mês ({@code /api/goals}).
 */
@RestController
public class InsightsController {

    /** Casos de uso. */
    private final InsightsUseCases insights;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param insights casos de uso
     * @param actor    quem está chamando
     */
    public InsightsController(InsightsUseCases insights, CurrentActor actor) {
        this.insights = insights;
        this.actor = actor;
    }

    /**
     * Resumo da tela inicial.
     *
     * @return o painel
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard() {
        return insights.dashboard(actor.requireOwner());
    }

    /**
     * Meta e realizado de um mês.
     *
     * @param month mês ({@code AAAA-MM-01}; padrão: o atual)
     * @return {@code month}, {@code goal} e {@code stats}
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/goals")
    public Map<String, Object> goals(@RequestParam(required = false) String month) {
        return insights.goals(actor.requireOwner(), month);
    }

    /**
     * Grava a meta do mês.
     *
     * @param body meta
     * @return a linha gravada
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/goals")
    public Map<String, Object> saveGoal(@RequestBody(required = false) Map<String, Object> body) {
        return insights.saveGoal(actor.requireOwner(), body == null ? Map.of() : body);
    }
}
