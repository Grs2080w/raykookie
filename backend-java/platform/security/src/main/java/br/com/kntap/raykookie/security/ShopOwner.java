package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A dona da loja: a única conta que entra no painel (G24), resolvida na subida a partir do
 * {@code SHOP_OWNER}. Se essa conta não existir, o servidor não sobe.
 *
 * <p>Única exceção, a instalação nova: com o cadastro ligado ({@code REGISTRATION_ENABLED=true}) e a
 * tabela {@code users} vazia, o servidor sobe com a dona "pendente" — as rotas da dona respondem 401 e o
 * cadastro aceita só o nome do {@code SHOP_OWNER}; ao cadastrar, a conta criada vira a dona
 * ({@link #claim(OwnerId)}).
 */
public final class ShopOwner {

    /** Usuário configurado em {@code SHOP_OWNER}. */
    private final String username;

    /** Id da conta da dona; vazio enquanto pendente. */
    private final AtomicReference<OwnerId> id;

    /**
     * Cria a dona.
     *
     * @param username usuário configurado
     * @param id       id da conta, ou nulo se pendente
     */
    private ShopOwner(String username, OwnerId id) {
        this.username = Objects.requireNonNull(username, "username");
        this.id = new AtomicReference<>(id);
    }

    /**
     * Dona já cadastrada.
     *
     * @param id       id da conta
     * @param username usuário
     * @return a dona
     */
    public static ShopOwner of(OwnerId id, String username) {
        return new ShopOwner(username, Objects.requireNonNull(id, "id"));
    }

    /**
     * Resolve a dona pelo nome configurado.
     *
     * @param username          valor de {@code SHOP_OWNER}
     * @param accounts          onde procurar a conta
     * @param registrationOpen  {@code REGISTRATION_ENABLED}
     * @return a dona (pendente só numa instalação nova com cadastro ligado)
     * @throws IllegalStateException se {@code SHOP_OWNER} estiver vazio ou a conta não existir
     */
    public static ShopOwner resolve(String username, OwnerSessions accounts, boolean registrationOpen) {

        // ── 1. Sem SHOP_OWNER não há dona a procurar.
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "SHOP_OWNER não foi definido: o servidor não sobe sem a dona da loja (G24).");
        }
        String name = username.strip();

        // ── 2. Conta existente: é a dona.
        Optional<OwnerId> existing = accounts.ownerByUsername(name);
        if (existing.isPresent()) {
            return new ShopOwner(name, existing.get());
        }

        // ── 3. Instalação nova com cadastro ligado: dona pendente até o cadastro.
        if (registrationOpen && !accounts.hasAnyAccount()) {
            return new ShopOwner(name, null);
        }
        throw new IllegalStateException(
                "SHOP_OWNER=" + name + " não existe na tabela users: o servidor não sobe (G24).");
    }

    /**
     * Usuário configurado em {@code SHOP_OWNER}.
     *
     * @return o usuário
     */
    public String username() {
        return username;
    }

    /**
     * Id da conta da dona.
     *
     * @return o id
     * @throws IllegalStateException se a dona ainda não foi cadastrada
     */
    public OwnerId id() {
        OwnerId current = id.get();
        if (current == null) {
            throw new IllegalStateException("A dona da loja ainda não foi cadastrada.");
        }
        return current;
    }

    /**
     * Diz se a dona ainda não foi cadastrada (instalação nova).
     *
     * @return verdadeiro enquanto pendente
     */
    public boolean isPending() {
        return id.get() == null;
    }

    /**
     * Diz se uma conta é a dona.
     *
     * @param other id de uma conta
     * @return verdadeiro se é a dona (sempre falso enquanto pendente)
     */
    public boolean is(OwnerId other) {
        return other != null && other.equals(id.get());
    }

    /**
     * Torna a conta recém-cadastrada a dona (só uma vez, só enquanto pendente).
     *
     * @param owner id da conta criada
     * @return verdadeiro se a conta virou a dona
     */
    public boolean claim(OwnerId owner) {
        return id.compareAndSet(null, Objects.requireNonNull(owner, "owner"));
    }
}
