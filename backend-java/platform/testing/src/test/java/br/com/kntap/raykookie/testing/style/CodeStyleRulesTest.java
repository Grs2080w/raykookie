package br.com.kntap.raykookie.testing.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prova que {@link CodeStyleRules} aceita o modelo do dono e recusa cada desvio, usando fontes
 * Java pequenos escritos aqui mesmo (e não só o código de hoje, que ainda é mínimo).
 */
class CodeStyleRulesTest {

    /** Método longo no formato pedido: blocos separados por linha em branco, numerados em ordem. */
    private static final String COMPLIANT = """
            package exemplo;

            /**
             * Classe de exemplo que segue o padrão.
             */
            public final class Conforme {

                /**
                 * Soma valores em blocos.
                 *
                 * @param a primeiro valor
                 * @return a soma
                 */
                public int somar(int a) {

                    // ── 1. Prepara os acumuladores do cálculo,
                    //       com explicação em duas linhas.
                    int x = a;
                    int y = a + 1;
                    int z = a + 2;

                    // ── 2. Combina os acumuladores no resultado.
                    int w = x + y;
                    int v = w + z;
                    int u = v + 1;
                    return u;
                }

                /**
                 * Método curto não precisa de blocos.
                 *
                 * @return sempre um
                 */
                private int curto() {
                    int a = 1;
                    return a;
                }
            }
            """;

    /** Método e construtor sem Javadoc, dentro de um tipo também sem Javadoc. */
    private static final String MISSING_JAVADOC = """
            package exemplo;

            public class SemDoc {

                SemDoc() {
                }

                void calcular() {
                }
            }
            """;

    /** Sete instruções sem nenhuma linha em branco entre elas. */
    private static final String LONG_WITHOUT_BLOCKS = """
            package exemplo;

            /**
             * Exemplo de método longo sem blocos.
             */
            class Longo {

                /**
                 * Faz tudo de uma vez.
                 */
                void tudoJunto() {
                    int a = 1;
                    int b = 2;
                    int c = 3;
                    int d = 4;
                    int e = 5;
                    int f = 6;
                    int g = 7;
                }
            }
            """;

    /** Blocos numerados 1 e 3: o 2 foi pulado. */
    private static final String BLOCKS_OUT_OF_ORDER = """
            package exemplo;

            /**
             * Exemplo de numeração errada.
             */
            class ForaDeOrdem {

                /**
                 * Pula um número.
                 */
                void pula() {

                    // ── 1. Primeiro bloco com texto suficiente.
                    int a = 1;
                    int b = 2;
                    int c = 3;

                    // ── 3. Segundo bloco numerado como três.
                    int d = 4;
                    int e = 5;
                    int f = 6;
                    int g = 7;
                }
            }
            """;

    /** Dois blocos, mas o segundo não tem comentário e o primeiro tem texto curto demais. */
    private static final String BAD_BLOCK_COMMENTS = """
            package exemplo;

            /**
             * Exemplo de comentários de bloco ruins.
             */
            class ComentarioRuim {

                /**
                 * Comentários fora do padrão.
                 */
                void ruim() {

                    // ── 1. curto
                    int a = 1;
                    int b = 2;
                    int c = 3;

                    int d = 4;
                    int e = 5;
                    int f = 6;
                    int g = 7;
                }
            }
            """;

    /**
     * O modelo do dono (incluindo comentário de bloco em duas linhas) não gera violação.
     */
    @Test
    @DisplayName("método no padrão do dono não gera violação")
    void compliantSourceHasNoViolations() {
        assertThat(CodeStyleRules.violationsInSource("Conforme.java", COMPLIANT)).isEmpty();
    }

    /**
     * Tipo, construtor e método sem Javadoc aparecem cada um com arquivo, linha e nome.
     */
    @Test
    @DisplayName("tipo, construtor e método sem Javadoc são apontados com arquivo e linha")
    void missingJavadocIsReported() {
        List<String> violations = CodeStyleRules.violationsInSource("SemDoc.java", MISSING_JAVADOC);

        assertThat(violations).containsExactly(
                "SemDoc.java:3 SemDoc: tipo sem Javadoc",
                "SemDoc.java:5 SemDoc: construtor sem Javadoc",
                "SemDoc.java:8 calcular: método sem Javadoc");
    }

    /**
     * Mais de 6 instruções num bloco só é recusado.
     */
    @Test
    @DisplayName("método com mais de 6 instruções sem blocos é recusado")
    void longMethodWithoutBlocksIsReported() {
        List<String> violations = CodeStyleRules.violationsInSource("Longo.java", LONG_WITHOUT_BLOCKS);

        assertThat(violations).anySatisfy(violation -> assertThat(violation)
                .startsWith("Longo.java:11 tudoJunto:")
                .contains("7 instruções")
                .contains("pelo menos 2 blocos"));
    }

    /**
     * Blocos numerados fora da sequência 1, 2, 3… são recusados.
     */
    @Test
    @DisplayName("blocos com numeração fora de ordem são recusados")
    void blocksOutOfOrderAreReported() {
        List<String> violations = CodeStyleRules.violationsInSource("ForaDeOrdem.java", BLOCKS_OUT_OF_ORDER);

        assertThat(violations).containsExactly(
                "ForaDeOrdem.java:19 pula: bloco 2 numerado como 3 (esperado \"// ── 2. ...\")");
    }

    /**
     * Bloco sem comentário e comentário curto demais são recusados.
     */
    @Test
    @DisplayName("bloco sem comentário ou com comentário curto é recusado")
    void badBlockCommentsAreReported() {
        List<String> violations = CodeStyleRules.violationsInSource("ComentarioRuim.java", BAD_BLOCK_COMMENTS);

        assertThat(violations).containsExactly(
                "ComentarioRuim.java:14 ruim: bloco 1 sem comentário \"// ── 1. <explicação>\"",
                "ComentarioRuim.java:18 ruim: bloco 2 sem comentário \"// ── 2. <explicação>\"");
    }

    /**
     * A varredura da raiz pega todo {@code src/main/java} (inclusive aninhado) e ignora testes e {@code target}.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("a varredura cobre src/main/java de todo módulo e ignora testes e target")
    void scanCoversEveryProductionSourceRoot(@TempDir Path root) throws IOException {

        // ── 1. Monta módulos falsos: um no topo, um aninhado, um teste e uma cópia em target.
        write(root.resolve("app/src/main/java/exemplo/SemDoc.java"), MISSING_JAVADOC);
        write(root.resolve("platform/web/src/main/java/exemplo/Longo.java"), LONG_WITHOUT_BLOCKS);
        write(root.resolve("app/src/test/java/exemplo/SemDoc.java"), MISSING_JAVADOC);
        write(root.resolve("app/target/src/main/java/exemplo/SemDoc.java"), MISSING_JAVADOC);

        // ── 2. Varre a raiz inteira.
        List<String> violations = CodeStyleRules.violations(root);

        // ── 3. Só os dois fontes de produção aparecem, com caminho relativo à raiz.
        assertThat(violations)
                .anyMatch(violation -> violation.startsWith("app/src/main/java/exemplo/SemDoc.java:3 "))
                .anyMatch(violation -> violation.startsWith("platform/web/src/main/java/exemplo/Longo.java:11 "))
                .noneMatch(violation -> violation.contains("src/test/"))
                .noneMatch(violation -> violation.contains("target/"));
    }

    /**
     * Grava um arquivo criando as pastas necessárias.
     *
     * @param file    caminho do arquivo
     * @param content conteúdo do arquivo
     * @throws IOException se não for possível gravar
     */
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
