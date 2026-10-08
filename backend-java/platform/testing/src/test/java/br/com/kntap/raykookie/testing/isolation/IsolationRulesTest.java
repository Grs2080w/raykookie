package br.com.kntap.raykookie.testing.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prova que {@link IsolationRules} acha caminhos que saem de backend-java.
 *
 * <p>Os caminhos de exemplo são montados com {@link #up(int)} em vez de escritos por extenso,
 * porque o próprio {@code IsolationTest} lê este arquivo e recusaria o texto literal.
 */
class IsolationRulesTest {

    /**
     * Caminhos que ficam dentro da raiz não são violação.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("caminhos relativos que ficam dentro de backend-java são aceitos")
    void pathsInsideTheRootAreAccepted(@TempDir Path root) throws IOException {

        // ── 1. Pai referenciado pelo módulo aninhado e código que sobe só até a raiz.
        write(root.resolve("pom.xml"), "<project></project>");
        write(root.resolve("platform/kernel/pom.xml"),
                "<project><parent><relativePath>" + up(2) + "pom.xml</relativePath></parent></project>");
        write(root.resolve("app/pom.xml"), "<project></project>");
        write(root.resolve("app/src/test/java/A.java"), "class A { Object p = Path.of(\"" + up(1) + "\"); }");

        // ── 2. Nada sai da raiz.
        assertThat(IsolationRules.violations(root)).isEmpty();
    }

    /**
     * Pai fora da raiz (explícito ou implícito) e caminhos que sobem demais são violação.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("pai fora da raiz e caminhos que sobem demais são recusados")
    void pathsLeavingTheRootAreReported(@TempDir Path root) throws IOException {

        // ── 1. Raiz com pai implícito (../pom.xml), módulo com pai fora e arquivos que sobem demais.
        write(root.resolve("pom.xml"), "<project><parent><artifactId>x</artifactId></parent></project>");
        write(root.resolve("app/pom.xml"),
                "<project><parent><relativePath>" + up(2) + "pom.xml</relativePath></parent></project>");
        write(root.resolve("app/src/main/java/B.java"), "class B {\n String f = \"" + up(2) + "shared/x.json\"; }");
        write(root.resolve("app/src/main/resources/application-prod.yml"), "dir: " + up(3) + "downloads\n");
        write(root.resolve("Dockerfile"), "COPY " + up(1) + "frontend /app\n");

        // ── 2. Arquivos em target/ e de outros tipos são ignorados.
        write(root.resolve("app/target/classes/application.yml"), "dir: " + up(5) + "x\n");
        write(root.resolve("README.txt"), up(4));

        // ── 3. Cada saída aparece com arquivo e linha.
        List<String> violations = IsolationRules.violations(root);
        assertThat(violations).containsExactlyInAnyOrder(
                "pom.xml:1 pai fora de backend-java: " + up(1) + "pom.xml (implícito)",
                "app/pom.xml:1 pai fora de backend-java: " + up(2) + "pom.xml",
                "app/src/main/java/B.java:2 caminho fora de backend-java: " + up(2) + "shared/x.json",
                "app/src/main/resources/application-prod.yml:1 caminho fora de backend-java: " + up(3) + "downloads",
                "Dockerfile:1 caminho fora de backend-java: " + up(1) + "frontend");
    }

    /**
     * Monta um caminho que sobe {@code levels} pastas.
     *
     * @param levels quantas pastas subir
     * @return o caminho, por exemplo {@code ../../} para 2
     */
    private static String up(int levels) {
        return (".".repeat(2) + "/").repeat(levels);
    }

    /**
     * Grava um arquivo criando as pastas necessárias.
     *
     * @param file    caminho do arquivo
     * @param content conteúdo
     * @throws IOException se não for possível gravar
     */
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
