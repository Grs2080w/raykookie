package br.com.kntap.raykookie.releases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Link assinado (vetor do Node) e o cabeçalho {@code Range}.
 */
class DownloadRulesTest {

    /** Assinador com o segredo do vetor. */
    private static final DownloadLinkSigner SIGNER = new DownloadLinkSigner("segredo-download");

    /**
     * Vetor do Node.
     */
    @Test
    @DisplayName("assinatura: segredo-download, server, 1900000000 ⇒ o mesmo do Node")
    void vector() {
        assertThat(SIGNER.sign("server", 1_900_000_000L))
                .isEqualTo("1900000000.rDAAQzxT_Hy8VmtTbE94nYAl_OYFlEDWd_tt9z6sVkk");
    }

    /**
     * Conferência.
     */
    @Test
    @DisplayName("vale para o app certo e no prazo; recusa adulterado, vencido, vazio e lixo")
    void verify() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String token = SIGNER.sign("server", 1_800_000_060L);
        assertThat(SIGNER.verify("server", token, now)).isTrue();
        assertThat(SIGNER.verify("client", token, now)).isFalse();
        assertThat(SIGNER.verify("server", token.substring(0, token.length() - 2) + "xx", now)).isFalse();
        assertThat(SIGNER.verify("server", SIGNER.sign("server", 1_799_999_990L), now)).isFalse();
        assertThat(SIGNER.verify("server", null, now)).isFalse();
        assertThat(SIGNER.verify("server", "lixo", now)).isFalse();
        assertThat(SIGNER.verify("server", "123.", now)).isFalse();
        assertThat(SIGNER.verify("server", ".abc", now)).isFalse();
    }

    /**
     * Trechos.
     */
    @Test
    @DisplayName("Range: início-fim, últimos N, sem fim, fora do arquivo (416) e cabeçalho estranho (arquivo todo)")
    void ranges() {
        assertThat(ByteRange.of(null, 100)).contains(new ByteRange(0, 99));
        assertThat(ByteRange.of("bytes=-", 100)).contains(new ByteRange(0, 99));
        assertThat(ByteRange.of("items=1-2", 100)).contains(new ByteRange(0, 99));
        assertThat(ByteRange.of("bytes=10-19", 100)).contains(new ByteRange(10, 19));
        assertThat(ByteRange.of("bytes=90-500", 100)).contains(new ByteRange(90, 99));
        assertThat(ByteRange.of("bytes=-10", 100)).contains(new ByteRange(90, 99));
        assertThat(ByteRange.of("bytes=-1000", 100)).contains(new ByteRange(0, 99));
        assertThat(ByteRange.of("bytes=50-", 100).orElseThrow().length()).isEqualTo(50);
        assertThat(ByteRange.of("bytes=999999999-", 100)).isEmpty();
        assertThat(ByteRange.of("bytes=99999999999999999999-", 100)).isEmpty();
        assertThat(ByteRange.of("bytes=20-10", 100)).isEmpty();
    }
}
