package br.com.kntap.raykookie.testing.isolation;

import br.com.kntap.raykookie.testing.style.SourceTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica que backend-java é autocontido: nenhum {@code pom.xml}, {@code Dockerfile},
 * {@code application*.yml} ou fonte Java aponta para arquivo fora da pasta.
 *
 * <p>Caminhos relativos ({@code ../...}) são resolvidos a partir da raiz do módulo dono do arquivo
 * (a pasta com o {@code pom.xml} mais próximo); o pai de cada pom, a partir da pasta do pom, com o
 * padrão do Maven ({@code ../pom.xml}) quando {@code relativePath} não é informado. Caminhos
 * absolutos que começam na pasta acima de backend-java também contam.
 */
public final class IsolationRules {

    /**
     * Trecho que sobe de pasta: {@code ..} isolado (não parte de {@code a..b} nem de {@code ...}),
     * seguido opcionalmente de {@code /resto/do/caminho}.
     */
    private static final Pattern RELATIVE_UP = Pattern.compile(
            "(?<![\\w.)\\]])\\.\\.(?:[/\\\\][^\\s\"'<>,;)}]*)?(?=[\\s\"'<>,;)}]|$)");

    /** Bloco {@code <parent>} de um pom. */
    private static final Pattern PARENT = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    /** Elemento {@code relativePath} dentro do bloco do pai (vazio = buscar só no repositório). */
    private static final Pattern RELATIVE_PATH = Pattern.compile("<relativePath\\s*(?:/>|>(.*?)</relativePath>)");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private IsolationRules() {
    }

    /**
     * Varre backend-java (menos {@code target/}) procurando caminhos que saem da pasta.
     *
     * @param root raiz de backend-java
     * @return uma violação por caminho, no formato {@code <arquivo>:<linha> <problema>: <caminho>}
     * @throws IOException se algum arquivo não puder ser lido
     */
    public static List<String> violations(Path root) throws IOException {
        Path realRoot = root.toRealPath();
        List<String> found = new ArrayList<>();
        for (Path file : SourceTree.files(realRoot, IsolationRules::isChecked)) {
            String content = Files.readString(file);
            String name = SourceTree.relative(realRoot, file);
            if (file.getFileName().toString().equals("pom.xml")) {
                parentViolation(realRoot, file, content).map(v -> name + ":" + v).ifPresent(found::add);
            }
            found.addAll(pathViolations(realRoot, file, content).stream().map(v -> name + ":" + v).toList());
        }
        return found;
    }

    /**
     * Diz se o arquivo é de um tipo que pode carregar caminho de build ou de execução.
     *
     * @param file arquivo encontrado
     * @return {@code true} para pom, Dockerfile, application*.yml/yaml e fonte Java
     */
    private static boolean isChecked(Path file) {
        String name = file.getFileName().toString();
        boolean yaml = name.startsWith("application") && (name.endsWith(".yml") || name.endsWith(".yaml"));
        return name.equals("pom.xml") || name.startsWith("Dockerfile") || yaml || name.endsWith(".java");
    }

    /**
     * Confere se o pai declarado pelo pom (explícito ou implícito) fica dentro da raiz.
     *
     * @param root    raiz de backend-java
     * @param pom     arquivo pom
     * @param content texto do pom
     * @return {@code "<linha> pai fora de backend-java: <caminho>"}, ou vazio se está tudo certo
     */
    private static Optional<String> parentViolation(Path root, Path pom, String content) {

        // ── 1. Sem pai, ou pai buscado só no repositório: nada a conferir.
        Matcher parent = PARENT.matcher(content);
        if (!parent.find()) {
            return Optional.empty();
        }
        Matcher relative = RELATIVE_PATH.matcher(parent.group(1));
        boolean explicit = relative.find();
        if (explicit && relative.group(1) == null) {
            return Optional.empty();
        }

        // ── 2. Resolve o caminho do pai (o padrão do Maven é ../pom.xml) a partir da pasta do pom.
        String path = explicit ? relative.group(1).strip() : "../pom.xml";
        int index = explicit ? parent.start(1) + relative.start(1) : parent.start();
        if (pom.getParent().resolve(path).normalize().startsWith(root)) {
            return Optional.empty();
        }
        String suffix = explicit ? "" : " (implícito)";
        return Optional.of(lineOf(content, index) + " pai fora de backend-java: " + path + suffix);
    }

    /**
     * Procura, linha a linha, caminhos relativos ou absolutos que saem da raiz.
     *
     * @param root    raiz de backend-java
     * @param file    arquivo verificado
     * @param content texto do arquivo
     * @return {@code "<linha> caminho fora de backend-java: <caminho>"} para cada ocorrência
     */
    private static List<String> pathViolations(Path root, Path file, String content) {

        // ── 1. Prepara a base dos caminhos relativos e o padrão dos absolutos que saem da raiz.
        Path moduleRoot = moduleRoot(root, file);
        Pattern absolute = Pattern.compile(Pattern.quote(root.getParent().toString()) + "/[^\\s\"'<>,;)}]*");
        String[] lines = content.split("\n", -1);
        List<String> found = new ArrayList<>();

        // ── 2. Confere cada linha (o relativePath do pom já foi conferido como pai).
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains("<relativePath>")) {
                continue;
            }
            for (String path : outside(moduleRoot, RELATIVE_UP.matcher(lines[index]), root)) {
                found.add((index + 1) + " caminho fora de backend-java: " + path);
            }
            for (String path : outside(Path.of("/"), absolute.matcher(lines[index]), root)) {
                found.add((index + 1) + " caminho fora de backend-java: " + path);
            }
        }
        return found;
    }

    /**
     * Os caminhos achados pelo padrão que, resolvidos a partir de {@code base}, ficam fora da raiz.
     *
     * @param base    pasta de onde os caminhos são resolvidos
     * @param matcher padrão já aplicado à linha
     * @param root    raiz de backend-java
     * @return os caminhos (como escritos no arquivo) que saem da raiz
     */
    private static List<String> outside(Path base, Matcher matcher, Path root) {
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            if (!base.resolve(matcher.group()).normalize().startsWith(root)) {
                paths.add(matcher.group());
            }
        }
        return paths;
    }

    /**
     * Raiz do módulo dono do arquivo: a pasta mais próxima, acima dele, que tem {@code pom.xml}.
     *
     * @param root raiz de backend-java (limite da busca)
     * @param file arquivo verificado
     * @return a raiz do módulo, ou a própria raiz se nenhuma pasta intermediária tem pom
     */
    private static Path moduleRoot(Path root, Path file) {
        Path dir = file.getParent();
        while (!dir.equals(root) && !Files.exists(dir.resolve("pom.xml"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    /**
     * Número da linha (a partir de 1) de uma posição do texto.
     *
     * @param content texto completo
     * @param index   posição no texto
     * @return a linha da posição
     */
    private static int lineOf(String content, int index) {
        return (int) content.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }
}
