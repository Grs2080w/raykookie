package br.com.kntap.raykookie.testing.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica o padrão de código pedido pelo dono do projeto, que o Checkstyle sozinho não enxerga:
 * Javadoc em todo tipo, construtor e método (inclusive private) e métodos com mais de
 * {@value #MAX_STATEMENTS_WITHOUT_BLOCKS} instruções divididos em blocos lógicos, cada um aberto por
 * {@code // ── N. explicação} numerado 1, 2, 3… sem pular.
 *
 * <p>Cada violação sai no formato {@code <arquivo>:<linha> <nome>: <regra violada>}.
 */
public final class CodeStyleRules {

    /** Quantas instruções de primeiro nível um corpo pode ter sem ser dividido em blocos. */
    public static final int MAX_STATEMENTS_WITHOUT_BLOCKS = 6;

    /** Cabeçalho de bloco (sem o {@code //}): número, ponto e pelo menos 10 caracteres de explicação. */
    private static final Pattern BLOCK_HEADER = Pattern.compile("^── (\\d+)\\. \\S.{9,}");

    /**
     * Uma violação ainda sem o nome do arquivo, para poder ordenar por linha.
     *
     * @param line linha do fonte onde o problema está
     * @param text nome do elemento e regra violada
     */
    private record Violation(int line, String text) {
    }

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private CodeStyleRules() {
    }

    /**
     * Verifica todo o código de produção ({@code src/main/java} de qualquer módulo, inclusive
     * {@code platform/*} e {@code modules/*}), ignorando {@code target/}.
     *
     * @param sourceRoot raiz de backend-java
     * @return as violações, com o caminho do arquivo relativo à raiz
     * @throws IOException se algum fonte não puder ser lido
     */
    public static List<String> violations(Path sourceRoot) throws IOException {
        return check(sourceRoot, SourceTree.javaSources(sourceRoot, "main"));
    }

    /**
     * Verifica todos os fontes Java de uma pasta qualquer (por exemplo, os testes guardiões).
     *
     * @param javaRoot pasta com fontes Java
     * @return as violações, com o caminho do arquivo relativo a {@code javaRoot}
     * @throws IOException se algum fonte não puder ser lido
     */
    public static List<String> violationsInTree(Path javaRoot) throws IOException {
        return check(javaRoot, SourceTree.files(javaRoot, SourceTree::isJava));
    }

    /**
     * Verifica um único fonte Java.
     *
     * @param fileName nome usado nas mensagens
     * @param source   texto do fonte
     * @return as violações em ordem de linha
     */
    public static List<String> violationsInSource(String fileName, String source) {

        // ── 1. Analisa o fonte; fonte que não compila no JavaParser já é uma violação.
        Optional<CompilationUnit> unit = SourceTree.parse(source);
        if (unit.isEmpty()) {
            return List.of(fileName + ":1 " + fileName + ": fonte não pôde ser analisado pelo JavaParser");
        }

        // ── 2. Coleta Javadoc ausente e blocos lógicos fora do padrão.
        String[] lines = source.split("\n", -1);
        List<Violation> found = new ArrayList<>(missingJavadoc(unit.get()));
        for (CallableDeclaration<?> callable : callables(unit.get())) {
            body(callable).ifPresent(body -> found.addAll(blockViolations(callable.getNameAsString(), body, lines)));
        }
        for (CompactConstructorDeclaration compact : unit.get().findAll(CompactConstructorDeclaration.class)) {
            found.addAll(blockViolations(compact.getNameAsString(), compact.getBody(), lines));
        }

        // ── 3. Ordena por linha e escreve no formato arquivo:linha.
        found.sort(Comparator.comparingInt(Violation::line));
        return found.stream().map(violation -> fileName + ":" + violation.line() + " " + violation.text()).toList();
    }

    /**
     * Verifica uma lista de arquivos e prefixa cada violação com o caminho relativo.
     *
     * @param root  pasta base dos caminhos nas mensagens
     * @param files fontes a verificar
     * @return as violações de todos os arquivos
     * @throws IOException se algum fonte não puder ser lido
     */
    private static List<String> check(Path root, List<Path> files) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : files) {
            found.addAll(violationsInSource(SourceTree.relative(root, file), Files.readString(file)));
        }
        return found;
    }

    /**
     * Todos os métodos e construtores (inclusive compactos de record) do fonte.
     *
     * @param unit árvore sintática do fonte
     * @return os métodos e construtores, em qualquer nível de aninhamento
     */
    private static List<CallableDeclaration<?>> callables(CompilationUnit unit) {
        List<CallableDeclaration<?>> callables = new ArrayList<>();
        unit.findAll(CallableDeclaration.class).forEach(callables::add);
        return callables;
    }

    /**
     * Procura tipos, construtores e métodos sem Javadoc.
     *
     * @param unit árvore sintática do fonte
     * @return uma violação por elemento sem Javadoc
     */
    private static List<Violation> missingJavadoc(CompilationUnit unit) {

        // ── 1. Tipos: classes, interfaces, records, enums e anotações.
        List<Violation> found = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            requireJavadoc(type, type, "tipo", found);
        }

        // ── 2. Construtores (inclusive compactos) e métodos.
        for (CallableDeclaration<?> callable : callables(unit)) {
            requireJavadoc(callable, callable, kindOf(callable), found);
        }
        for (CompactConstructorDeclaration compact : unit.findAll(CompactConstructorDeclaration.class)) {
            requireJavadoc(compact, compact, "construtor", found);
        }
        return found;
    }

    /**
     * Registra uma violação se o elemento não tiver Javadoc.
     *
     * @param node  elemento verificado
     * @param named o mesmo elemento visto como algo que tem nome
     * @param kind  "tipo", "construtor" ou "método", usado na mensagem
     * @param found lista onde a violação é acrescentada
     */
    private static void requireJavadoc(Node node, NodeWithSimpleName<?> named, String kind, List<Violation> found) {
        boolean documented = node.getComment().filter(Comment::isJavadocComment).isPresent();
        if (!documented) {
            int line = SourceTree.line(named.getName());
            found.add(new Violation(line, named.getNameAsString() + ": " + kind + " sem Javadoc"));
        }
    }

    /**
     * Nome do tipo de elemento para as mensagens.
     *
     * @param callable método ou construtor
     * @return "construtor" ou "método"
     */
    private static String kindOf(CallableDeclaration<?> callable) {
        return callable instanceof ConstructorDeclaration ? "construtor" : "método";
    }

    /**
     * Corpo do método ou construtor, se houver (métodos abstratos e de interface não têm).
     *
     * @param callable método ou construtor
     * @return o corpo, ou vazio
     */
    private static Optional<BlockStmt> body(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration method) {
            return method.getBody();
        }
        return Optional.of(((ConstructorDeclaration) callable).getBody());
    }

    /**
     * Confere a divisão em blocos lógicos de um corpo com mais de
     * {@value #MAX_STATEMENTS_WITHOUT_BLOCKS} instruções de primeiro nível.
     *
     * @param name  nome do método ou construtor dono do corpo
     * @param body  corpo verificado
     * @param lines linhas do fonte original (índice 0 = linha 1)
     * @return as violações de blocos deste corpo
     */
    private static List<Violation> blockViolations(String name, BlockStmt body, String[] lines) {

        // ── 1. Corpos curtos não precisam de blocos.
        List<Statement> statements = body.getStatements();
        if (statements.size() <= MAX_STATEMENTS_WITHOUT_BLOCKS) {
            return List.of();
        }

        // ── 2. Separa os blocos pelas linhas em branco; um bloco só é violação por si.
        List<Statement> blockStarts = blockStarts(statements, lines);
        if (blockStarts.size() < 2) {
            String rule = "método com " + statements.size() + " instruções precisa de pelo menos 2 blocos"
                    + " separados por linha em branco";
            return List.of(new Violation(SourceTree.line(body), name + ": " + rule));
        }

        // ── 3. Cada bloco precisa do cabeçalho numerado na sequência 1, 2, 3…
        List<Violation> found = new ArrayList<>();
        for (int index = 0; index < blockStarts.size(); index++) {
            headerViolation(name, index + 1, blockStarts.get(index), lines).ifPresent(found::add);
        }
        return found;
    }

    /**
     * Acha a primeira instrução de cada bloco: começa bloco novo a instrução separada da anterior
     * por ao menos uma linha em branco no fonte original.
     *
     * @param statements instruções de primeiro nível do corpo
     * @param lines      linhas do fonte original
     * @return a primeira instrução de cada bloco, em ordem
     */
    private static List<Statement> blockStarts(List<Statement> statements, String[] lines) {
        List<Statement> starts = new ArrayList<>(List.of(statements.get(0)));
        for (int index = 1; index < statements.size(); index++) {
            int previousEnd = statements.get(index - 1).getEnd().map(position -> position.line).orElse(0);
            if (hasBlankLine(lines, previousEnd, SourceTree.line(statements.get(index)))) {
                starts.add(statements.get(index));
            }
        }
        return starts;
    }

    /**
     * Diz se há uma linha em branco estritamente entre duas linhas do fonte.
     *
     * @param lines linhas do fonte original
     * @param after linha de onde a busca começa (exclusiva)
     * @param until linha onde a busca para (exclusiva)
     * @return {@code true} se alguma linha intermediária está em branco
     */
    private static boolean hasBlankLine(String[] lines, int after, int until) {
        for (int line = after + 1; line < until; line++) {
            if (lines[line - 1].isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Confere o comentário de linha logo acima da primeira instrução de um bloco.
     *
     * @param name     nome do método, para a mensagem
     * @param expected número que o bloco deveria ter
     * @param start    primeira instrução do bloco
     * @param lines    linhas do fonte original
     * @return a violação, ou vazio se o cabeçalho está certo
     */
    private static Optional<Violation> headerViolation(String name, int expected, Statement start, String[] lines) {

        // ── 1. Lê o cabeçalho (primeira linha do comentário que pode continuar em várias linhas).
        int line = SourceTree.line(start);
        Matcher header = BLOCK_HEADER.matcher(headerAbove(lines, line));
        if (!header.find()) {
            String rule = "bloco " + expected + " sem comentário \"// ── " + expected + ". <explicação>\"";
            return Optional.of(new Violation(line, name + ": " + rule));
        }

        // ── 2. Confere se a numeração segue a sequência.
        int actual = Integer.parseInt(header.group(1));
        if (actual != expected) {
            String rule = "bloco " + expected + " numerado como " + actual
                    + " (esperado \"// ── " + expected + ". ...\")";
            return Optional.of(new Violation(line, name + ": " + rule));
        }
        return Optional.empty();
    }

    /**
     * Texto da primeira linha do grupo de comentários {@code //} colado acima de uma linha.
     *
     * @param lines linhas do fonte original
     * @param line  linha da instrução (a partir de 1)
     * @return o texto sem o {@code //} inicial, ou vazio se não há comentário colado
     */
    private static String headerAbove(String[] lines, int line) {
        int top = line;
        while (top > 1 && lines[top - 2].strip().startsWith("//")) {
            top--;
        }
        if (top == line) {
            return "";
        }
        return lines[top - 1].strip().substring(2).strip();
    }
}
