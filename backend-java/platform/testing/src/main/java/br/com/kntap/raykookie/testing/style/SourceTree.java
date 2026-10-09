package br.com.kntap.raykookie.testing.style;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Utilitários para percorrer a árvore de backend-java e analisar fontes Java com o JavaParser,
 * compartilhados pelos guardiões de estilo, de dublês de teste e de isolamento.
 */
public final class SourceTree {

    /** Pastas que nunca contêm fonte do projeto (saída de build, wrapper, controle de versão). */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", ".mvn", ".git", "node_modules");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private SourceTree() {
    }

    /**
     * Lista os arquivos sob {@code root} que passam no filtro, pulando {@code target/} e afins.
     *
     * @param root   pasta de onde a varredura começa
     * @param accept filtro aplicado a cada arquivo encontrado
     * @return os arquivos aceitos, em ordem alfabética de caminho
     * @throws IOException se alguma pasta não puder ser lida
     */
    public static List<Path> files(Path root, Predicate<Path> accept) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            /**
             * Pula as pastas de build e de ferramentas.
             *
             * @param dir   pasta visitada
             * @param attrs atributos da pasta
             * @return se a pasta deve ser percorrida
             */
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                boolean skipped = !dir.equals(root) && SKIPPED_DIRECTORIES.contains(dir.getFileName().toString());
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            /**
             * Guarda o arquivo se ele passar no filtro.
             *
             * @param file  arquivo visitado
             * @param attrs atributos do arquivo
             * @return sempre continuar
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (accept.test(file)) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }

    /**
     * Lista os fontes Java que estão sob alguma pasta {@code src/<kind>/java} de qualquer módulo.
     *
     * @param root raiz de backend-java
     * @param kind {@code main} para produção ou {@code test} para testes
     * @return os arquivos {@code .java} encontrados
     * @throws IOException se alguma pasta não puder ser lida
     */
    public static List<Path> javaSources(Path root, String kind) throws IOException {
        Path marker = Path.of("src", kind, "java");
        return files(root, file -> isJava(file) && hasAncestor(root, file, marker));
    }

    /**
     * Diz se o arquivo é um fonte Java.
     *
     * @param file arquivo a testar
     * @return {@code true} se termina em {@code .java}
     */
    public static boolean isJava(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    /**
     * Diz se, entre a raiz e o arquivo, existe uma pasta cujo caminho termina em {@code marker}.
     *
     * @param root   raiz da varredura
     * @param file   arquivo encontrado
     * @param marker trecho final da pasta procurada (ex.: {@code src/main/java})
     * @return {@code true} se alguma pasta ancestral termina em {@code marker}
     */
    private static boolean hasAncestor(Path root, Path file, Path marker) {
        Stream<Path> ancestors = Stream.iterate(file.getParent(), dir -> dir != null && dir.startsWith(root),
                Path::getParent);
        return ancestors.anyMatch(dir -> dir.endsWith(marker));
    }

    /**
     * Escreve o caminho do arquivo relativo à raiz, sempre com {@code /}.
     *
     * @param root raiz da varredura
     * @param file arquivo dentro da raiz
     * @return o caminho relativo, por exemplo {@code app/src/main/java/A.java}
     */
    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * Analisa um fonte Java na versão da linguagem usada pelo projeto.
     *
     * @param source texto do fonte
     * @return a árvore sintática, ou vazio se o fonte não pôde ser analisado
     */
    public static Optional<CompilationUnit> parse(String source) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    /**
     * Linha onde o nó começa no fonte original.
     *
     * @param node nó da árvore sintática
     * @return a linha (a partir de 1), ou 0 se o nó não tem posição
     */
    public static int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
