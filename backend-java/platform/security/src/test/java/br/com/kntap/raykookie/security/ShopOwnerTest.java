package br.com.kntap.raykookie.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dona da loja é resolvida pelo SHOP_OWNER; sem ela, o servidor não sobe (G24).
 */
class ShopOwnerTest {

    /**
     * Contas conhecidas, por nome em minúsculas.
     */
    private static final class KnownAccounts implements OwnerSessions {

        /** Contas por nome. */
        private final Map<String, Long> accounts = Map.of("ana", 7L);

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
    }

    /**
     * Conta existente: resolve o id, sem diferenciar maiúsculas e sem espaços nas pontas.
     */
    @Test
    @DisplayName("SHOP_OWNER existente vira o id da conta")
    void resolvesExistingAccount() {
        ShopOwner owner = ShopOwner.resolve(" Ana ", new KnownAccounts());
        assertThat(owner.id()).isEqualTo(new OwnerId(7));
        assertThat(owner.is(new OwnerId(7))).isTrue();
        assertThat(owner.is(new OwnerId(8))).isFalse();
    }

    /**
     * Conta inexistente ou SHOP_OWNER vazio: erro que impede a subida, citando a causa.
     */
    @Test
    @DisplayName("SHOP_OWNER vazio ou inexistente: o servidor não sobe")
    void missingAccountStopsStartup() {
        assertThatThrownBy(() -> ShopOwner.resolve("bruna", new KnownAccounts()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER=bruna");
        assertThatThrownBy(() -> ShopOwner.resolve(" ", new KnownAccounts()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER");
        assertThatThrownBy(() -> ShopOwner.resolve(null, new KnownAccounts()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHOP_OWNER");
    }
}
