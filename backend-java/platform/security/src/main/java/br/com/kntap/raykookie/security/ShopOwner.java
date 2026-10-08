package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Objects;

/**
 * A dona da loja: a única conta que entra no painel (G24). Resolvida na subida a partir do
 * {@code SHOP_OWNER}; se essa conta não existir, o servidor não sobe.
 *
 * @param id       id da conta em {@code users}
 * @param username nome de usuário configurado
 */
public record ShopOwner(OwnerId id, String username) {

    /**
     * Confere os campos.
     *
     * @param id       id da conta
     * @param username nome de usuário
     */
    public ShopOwner {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
    }

    /**
     * Resolve a dona pelo nome configurado.
     *
     * @param username valor de {@code SHOP_OWNER}
     * @param sessions onde procurar a conta
     * @return a dona
     * @throws IllegalStateException se {@code SHOP_OWNER} estiver vazio ou a conta não existir
     */
    public static ShopOwner resolve(String username, OwnerSessions sessions) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "SHOP_OWNER não foi definido: o servidor não sobe sem a dona da loja (G24).");
        }
        OwnerId id = sessions.ownerByUsername(username.strip())
                .orElseThrow(() -> new IllegalStateException(
                        "SHOP_OWNER=" + username + " não existe na tabela users: o servidor não sobe (G24)."));
        return new ShopOwner(id, username.strip());
    }

    /**
     * Diz se uma conta é a dona.
     *
     * @param other id de uma conta
     * @return verdadeiro se é a dona
     */
    public boolean is(OwnerId other) {
        return id.equals(other);
    }
}
