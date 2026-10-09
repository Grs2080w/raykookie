package br.com.kntap.raykookie.identity.domain;

import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Uma conta do painel.
 *
 * @param id           id em {@code users}
 * @param username     usuário
 * @param passwordHash hash da senha ({@code scrypt$…})
 */
public record OwnerAccount(OwnerId id, String username, String passwordHash) {
}
