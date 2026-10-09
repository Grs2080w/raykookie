package br.com.kntap.raykookie.quality;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.style.CodeStyleRules;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garante o padrão de código pedido pelo dono do projeto: métodos longos divididos em
 * blocos lógicos, cada bloco aberto por um comentário numerado "// ── N. explicação".
 */
class CodeStyleTest {

    /**
     * Percorre todo o código de produção de backend-java e falha listando cada violação.
     *
     * @throws IOException se algum fonte não puder ser lido
     */
    @Test
    @DisplayName("todo método com mais de 6 instruções tem blocos lógicos comentados")
    void logicalBlocksAreDocumented() throws IOException {

        // ── 1. Localiza a raiz de backend-java a partir deste módulo de teste.
        Path root = Path.of("..").toRealPath();

        // ── 2. Analisa os fontes de produção e coleta as violações.
        List<String> violations = CodeStyleRules.violations(root);

        // ── 3. Falha mostrando arquivo:linha de cada problema.
        assertThat(violations).isEmpty();
    }

    /**
     * Os próprios guardiões (estes testes de qualidade) seguem o mesmo padrão.
     *
     * @throws IOException se algum fonte não puder ser lido
     */
    @Test
    @DisplayName("os testes guardiões também seguem o padrão de código")
    void qualityGuardsFollowTheStandard() throws IOException {
        Path guards = Path.of("src/test/java/br/com/kntap/raykookie/quality").toRealPath();

        assertThat(CodeStyleRules.violationsInTree(guards)).isEmpty();
    }
}
