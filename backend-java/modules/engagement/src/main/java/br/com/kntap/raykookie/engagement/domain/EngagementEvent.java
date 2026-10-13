package br.com.kntap.raykookie.engagement.domain;

import java.time.Instant;

/**
 * Um evento de medição já conferido. Nada aqui identifica a pessoa: só o que ela fez e em que tela.
 *
 * @param at     quando aconteceu
 * @param name   nome do evento (lista fechada em {@link EngagementCatalog})
 * @param route  tela ou página, sem parâmetros (vazio se não se aplica)
 * @param itemId item do cardápio ({@code <id>} da receita ou {@code combo-<id>}; vazio se não se aplica)
 * @param detail passo, método, origem ou resultado (vazio se não se aplica)
 * @param qty    quantidade (0 se não se aplica)
 */
public record EngagementEvent(Instant at, String name, String route, String itemId, String detail, int qty) {
}
