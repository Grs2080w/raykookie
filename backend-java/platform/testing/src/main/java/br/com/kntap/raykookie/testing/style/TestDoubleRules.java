package br.com.kntap.raykookie.testing.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regra T04: os testes de pagamentos, devoluções e pedidos rodam contra o banco real; nenhum deles
 * pode trocar um {@code *Repository} por dublê do Mockito ({@code mock(...)}, {@code spy(...)},
 * {@code @Mock}, {@code @Spy}, {@code @MockitoBean}, {@code @MockitoSpyBean}, {@code @MockBean}).
 *
 * <p>É uma varredura dos fontes de teste com JavaParser porque as classes de teste de outros
 * módulos não estão no classpath do app, onde o ArchUnit roda.
 */
public final class TestDoubleRules {

    /** Pacotes cujos testes não podem fingir repositório: payments, refunds e orders (e subpacotes). */
    private static final Pattern MONEY_PACKAGE = Pattern.compile("(^|.*\\.)(payments|refunds|orders)(\\..*)?$");

    /** Métodos do Mockito que criam dublê a partir de uma classe. */
    private static final Set<String> DOUBLE_FACTORIES = Set.of("mock", "spy");

    /** Anotações que criam dublê num campo ou parâmetro. */
    private static final Set<String> DOUBLE_ANNOTATIONS = Set.of("Mock", "Spy", "MockitoBean", "MockitoSpyBean",
            "MockBean", "SpyBean");

    /**
     * Um dublê de repositório encontrado, ainda sem o nome do arquivo.
     *
     * @param line linha do fonte
     * @param type nome simples do tipo fingido
     */
    private record Finding(int line, String type) {
    }

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private TestDoubleRules() {
    }

    /**
     * Varre os fontes de teste ({@code src/test/java}) de todos os módulos.
     *
     * @param root raiz de backend-java
     * @return as violações, com o caminho do arquivo relativo à raiz
     * @throws IOException se algum fonte não puder ser lido
     */
    public static List<String> repositoryMocks(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : SourceTree.javaSources(root, "test")) {
            found.addAll(repositoryMocksInSource(SourceTree.relative(root, file), Files.readString(file)));
        }
        return found;
    }

    /**
     * Verifica um único fonte de teste.
     *
     * @param fileName nome usado nas mensagens
     * @param source   texto do fonte
     * @return uma violação por dublê de repositório, em ordem de linha
     */
    public static List<String> repositoryMocksInSource(String fileName, String source) {

        // ── 1. Só interessam testes que estão em pacotes de dinheiro.
        Optional<CompilationUnit> unit = SourceTree.parse(source);
        if (unit.isEmpty() || !isMoneyPackage(unit.get())) {
            return List.of();
        }

        // ── 2. Junta as três formas de criar dublê: chamada, campo anotado e parâmetro anotado.
        List<Finding> findings = new ArrayList<>(factoryCalls(unit.get()));
        unit.get().findAll(FieldDeclaration.class).forEach(field -> annotated(field, field.getElementType())
                .ifPresent(findings::add));
        unit.get().findAll(Parameter.class).forEach(parameter -> annotated(parameter, parameter.getType())
                .ifPresent(findings::add));

        // ── 3. Ordena por linha e escreve no formato arquivo:linha.
        findings.sort(Comparator.comparingInt(Finding::line));
        return findings.stream()
                .map(finding -> fileName + ":" + finding.line() + " " + finding.type()
                        + ": dublê de repositório em teste de dinheiro (T04)")
                .toList();
    }

    /**
     * Diz se o fonte está num pacote de pagamentos, devoluções ou pedidos.
     *
     * @param unit árvore sintática do fonte
     * @return {@code true} se o pacote é de dinheiro
     */
    private static boolean isMoneyPackage(CompilationUnit unit) {
        return unit.getPackageDeclaration()
                .map(declaration -> MONEY_PACKAGE.matcher(declaration.getNameAsString()).matches())
                .orElse(false);
    }

    /**
     * Acha {@code mock(X.class)}/{@code spy(X.class)} (com ou sem {@code Mockito.}) de repositório.
     *
     * @param unit árvore sintática do fonte
     * @return um achado por chamada
     */
    private static List<Finding> factoryCalls(CompilationUnit unit) {
        List<Finding> findings = new ArrayList<>();
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            boolean factory = DOUBLE_FACTORIES.contains(call.getNameAsString()) && !call.getArguments().isEmpty();
            Expression first = factory ? call.getArgument(0) : null;
            if (first instanceof ClassExpr classExpr && isRepository(classExpr.getType())) {
                findings.add(new Finding(SourceTree.line(call), simpleName(classExpr.getType())));
            }
        }
        return findings;
    }

    /**
     * Acha campo ou parâmetro de repositório marcado com anotação de dublê.
     *
     * @param <N>  tipo do nó anotado
     * @param node campo ou parâmetro
     * @param type tipo declarado
     * @return o achado, ou vazio se não é dublê de repositório
     */
    private static <N extends Node & NodeWithAnnotations<?>> Optional<Finding> annotated(N node, Type type) {
        boolean isDouble = node.getAnnotations().stream()
                .anyMatch(annotation -> DOUBLE_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
        if (!isDouble || !isRepository(type)) {
            return Optional.empty();
        }
        return Optional.of(new Finding(SourceTree.line(type), simpleName(type)));
    }

    /**
     * Diz se o tipo é um repositório (nome terminado em {@code Repository}).
     *
     * @param type tipo a testar
     * @return {@code true} se é repositório
     */
    private static boolean isRepository(Type type) {
        return simpleName(type).endsWith("Repository");
    }

    /**
     * Nome simples do tipo, sem pacote nem genéricos.
     *
     * @param type tipo
     * @return o nome simples, por exemplo {@code OrderRepository}
     */
    private static String simpleName(Type type) {
        String name = type.asString().replaceAll("<.*>", "");
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
