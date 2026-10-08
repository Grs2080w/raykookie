package br.com.kntap.raykookie.releases;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.releases.application.Releases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Manifesto ({@code backend/tests/downloads.test.ts}).
 */
class ManifestIT {

    /**
     * Arquivo válido e nome com caminho.
     *
     * @param dir pasta
     * @throws IOException se não der para gravar
     */
    @Test
    @DisplayName("lista só o que tem arquivo válido; recusa nome de arquivo com caminho; sem manifesto é vazio")
    void manifest(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("raykookie-1.0.0+3.apk"), "apk");
        Files.writeString(dir.resolve("manifest.json"), """
                {"client":{"app":"client","version":"1.0.0","build":3,"file":"raykookie-1.0.0+3.apk","sha256":"x"},
                 "server":{"app":"server","version":"1.0.0","build":1,"file":"../../etc/passwd","sha256":"y"}}""");
        Releases releases = new Releases(dir.toString());
        assertThat(releases.manifest().get("client")).containsEntry("version", "1.0.0");
        assertThat(releases.manifest()).doesNotContainKey("server");
        assertThat(new Releases(dir.resolve("nao-existe").toString()).manifest()).isEmpty();
        Files.writeString(dir.resolve("manifest.json"), "[1,2]");
        assertThat(releases.manifest()).isEmpty();
    }
}
