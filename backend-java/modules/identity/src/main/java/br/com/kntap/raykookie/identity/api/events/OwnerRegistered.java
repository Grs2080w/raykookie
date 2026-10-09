package br.com.kntap.raykookie.identity.api.events;

import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Uma conta de dona foi cadastrada. O {@code catalog} escuta e cria as configurações e as categorias
 * padrão ({@code backend/lib/defaults.ts}), como o {@code registerUser} do Node fazia.
 *
 * @param ownerId  id da conta criada
 * @param username usuário
 */
public record OwnerRegistered(OwnerId ownerId, String username) {
}
