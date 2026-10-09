package br.com.kntap.raykookie.customers.api.events;

import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Um cliente fez uma solicitação sobre os próprios dados (LGPD). O {@code notifications} avisa a dona.
 *
 * @param owner     a dona da loja
 * @param requestId id da solicitação
 * @param type      tipo ({@code acesso}, {@code revogacao}…)
 */
public record PrivacyRequestCreated(OwnerId owner, long requestId, String type) {
}
