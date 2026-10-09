package br.com.kntap.raykookie.quality;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.isolation.IsolationRules;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garante que backend-java é autocontido: nenhum pom, Dockerfile, application*.yml ou fonte Java
 * aponta para arquivo fora da pasta (o backend Node e o resto do repositório não entram no build).
 */
class IsolationTest {

    /**
     * Varre backend-java (menos {@code target/}) e falha listando cada caminho que sai da pasta.
     *
     * @throws IOException se algum arquivo não puder ser lido
     */
    @Test
    @DisplayName("nada em backend-java referencia arquivo fora da pasta")
    void nothingReferencesFilesOutsideBackendJava() throws IOException {
        Path root = Path.of("..").toRealPath();

        assertThat(IsolationRules.violations(root)).isEmpty();
    }
}
