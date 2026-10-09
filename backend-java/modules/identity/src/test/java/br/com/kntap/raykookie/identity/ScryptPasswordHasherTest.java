package br.com.kntap.raykookie.identity;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Senhas criadas pelo Node continuam valendo no Java.
 */
class ScryptPasswordHasherTest {

    /** Vetor de ouro do Node (Apêndice B do plano). */
    private static final String NODE_HASH = "scrypt$00112233445566778899aabbccddeeff$"
            + "216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a4"
            + "2dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff";

    /** O hasher. */
    private final ScryptPasswordHasher hasher = new ScryptPasswordHasher();

    /**
     * Hash gerado pelo Node: senha certa confere, errada não.
     */
    @Test
    @DisplayName("confere o hash gerado pelo Node")
    void verifiesNodeHash() {
        assertThat(hasher.verify("senha-de-teste-123", NODE_HASH)).isTrue();
        assertThat(hasher.verify("senha-de-teste-124", NODE_HASH)).isFalse();
        assertThat(hasher.verify("", NODE_HASH)).isFalse();
    }

    /**
     * Hash novo no formato do Node, que confere a própria senha e tem sal aleatório.
     */
    @Test
    @DisplayName("hash novo: scrypt$<32 hex>$<128 hex>, sal diferente a cada vez")
    void newHashFormat() {
        String first = hasher.hash("ação-çãõ-ñ-1234");
        assertThat(first).matches("scrypt\\$[0-9a-f]{32}\\$[0-9a-f]{128}");
        assertThat(hasher.verify("ação-çãõ-ñ-1234", first)).isTrue();
        assertThat(hasher.hash("ação-çãõ-ñ-1234")).isNotEqualTo(first);
    }

    /**
     * Formatos estranhos nunca conferem (nem lançam).
     *
     * @param stored hash guardado inválido
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "scrypt", "scrypt$", "scrypt$$", "bcrypt$00$00", "scrypt$zz$00", "scrypt$00$zz",
        "scrypt$00$00$00"})
    @DisplayName("hash guardado inválido: não confere")
    void invalidStoredHash(String stored) {
        assertThat(hasher.verify("qualquer", stored)).isFalse();
    }

    /**
     * O hash fictício (usado quando a conta não existe) roda o scrypt e não confere.
     */
    @Test
    @DisplayName("hash fictício: não confere")
    void dummyHash() {
        assertThat(hasher.verify("qualquer", ScryptPasswordHasher.DUMMY_HASH)).isFalse();
        assertThat(hasher.verify("qualquer", null)).isFalse();
    }
}
