package br.com.kntap.raykookie.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dona da loja é resolvida pelo SHOP_OWNER; sem ela, o servidor não sobe (G24), salvo na instalação nova
 * com o cadastro ligado.
 */
class ShopOwnerTest {

    /**
     * Contas conhecidas, por nome em minúsculas.
     */
    private static final class KnownAccounts implements OwnerSessions {

        /** Contas por nome. */
        private final Map<String, Long> accounts = new HashMap<>();

        /**
         * Cria a lista de contas.
         *
         * @param names nomes, com ids 7, 8, 9…
         */
        KnownAccounts(String... names) {
            for (int i = 0; i < names.length; i++) {
                accounts.put(names[i], 7L + i);
            }
        }

        /**
         * Nenhuma sessão.
         *
         * @param tokenHash hash do token
         * @return vazio
         */
        @Override
        public Optional<OwnerId> owner(String tokenHash) {
            return Optional.empty();
        }

        /**
         * Procura sem diferenciar maiúsculas.
         *
         * @param username nome
         * @return o id, se existir
         */
        @Override
        public Optional<OwnerId> ownerByUsername(String username) {
            return Optional.ofNullable(accounts.get(username.toLowerCase())).map(OwnerId::new);
        }

        /**
         * Diz se há contas.
         *
         * @return verdadeiro se a lista não está vazia
         */
        @Override
        public boolean hasAnyAccount() {
            return !accounts.isEmpty();
        }
    }

    /**
     * Conta existente: resolve o id, sem diferenciar maiúsculas e sem espaços nas pontas.
     */
    @Test
    @DisplayName("SHOP_OWNER existente vira o id da conta")
    void resolvesExistingAccount() {
        ShopOwner owner = ShopOwner.resolve(" Ana ", new KnownAccounts("ana"), false);
        assertThat(owner.id()).isEqualTo(new OwnerId(7));
        assertThat(owner.username()).isEqualTo("Ana");
        assertThat(owner.isPending()).isFalse();
        assertThat(owner.is(new OwnerId(7))).isTrue();
        assertThat(owner.is(new OwnerId(8))).isFalse();
        assertThat(owner.claim(new OwnerId(8))).isFalse();
    }

    /**
     * Conta inexistente ou SHOP_OWNER vazio: erro que impede a subida, citando a causa.
     */
    @Test
    @DisplayName("SHOP_OWNER vazio ou inexistente: o servidor não sobe")
    void missingAccountStopsStartup() {
        assertThatThrownBy(() -> ShopOwner.resolve("bruna", new KnownAccounts("ana"), false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER=bruna");
        assertThatThrownBy(() -> ShopOwner.resolve(" ", new KnownAccounts("ana"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER");
        assertThatThrownBy(() -> ShopOwner.resolve(null, new KnownAccounts(), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER");
    }

    /**
     * Cadastro ligado não abre exceção se já existem contas: só a instalação nova fica pendente.
     */
    @Test
    @DisplayName("cadastro ligado com contas existentes e sem a dona: o servidor não sobe")
    void registrationDoesNotHideAMissingOwner() {
        assertThatThrownBy(() -> ShopOwner.resolve("bruna", new KnownAccounts("ana"), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER=bruna");
    }

    /**
     * Instalação nova com cadastro ligado: dona pendente, que ninguém é até o cadastro.
     */
    @Test
    @DisplayName("instalação nova com cadastro ligado: dona pendente até o cadastro")
    void freshInstallIsPending() {

        // ── 1. Sem contas, cadastro ligado.
        ShopOwner owner = ShopOwner.resolve("rayka", new KnownAccounts(), true);

        // ── 2. Pendente: ninguém é a dona e o id não existe.
        assertThat(owner.isPending()).isTrue();
        assertThat(owner.is(new OwnerId(1))).isFalse();
        assertThatThrownBy(owner::id).isInstanceOf(IllegalStateException.class);

        // ── 3. O cadastro reivindica uma vez só.
        assertThat(owner.claim(new OwnerId(5))).isTrue();
        assertThat(owner.claim(new OwnerId(6))).isFalse();
        assertThat(owner.id()).isEqualTo(new OwnerId(5));
        assertThat(ShopOwner.of(new OwnerId(5), "rayka").is(new OwnerId(5))).isTrue();
    }
}
