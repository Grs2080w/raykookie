package br.com.kntap.raykookie.testing.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Regras de fronteira entre módulos de negócio: de fora de um módulo, só o pacote {@code api}
 * dele é visível. Cada módulo de {@code modules/<nome>} vive no pacote {@code br.com.kntap.raykookie.<nome>}.
 */
public final class ModuleRules {

    /** Módulos de negócio previstos no plano (pasta {@code modules/}), na ordem da estrutura. */
    public static final List<String> PLANNED_MODULES = List.of("identity", "customers", "notifications", "catalog",
            "purchasing", "storefront", "orders", "payments", "refunds", "feedback", "insights", "releases");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private ModuleRules() {
    }

    /**
     * Regra 3 para um módulo do pacote base real.
     *
     * @param module nome do módulo (ex.: {@code payments})
     * @return a regra: classes fora do módulo só acessam {@code br.com.kntap.raykookie.<module>.api..}
     */
    public static ArchRule onlyApiIsShared(String module) {
        return onlyApiIsShared(ArchitectureRules.BASE_PACKAGE, module);
    }

    /**
     * Regra 3 para um módulo de um pacote base qualquer (usado também pelos testes da própria regra).
     *
     * @param basePackage pacote base dos módulos
     * @param module      nome do módulo
     * @return a regra: classes fora do módulo só acessam {@code <basePackage>.<module>.api..}
     */
    public static ArchRule onlyApiIsShared(String basePackage, String module) {
        String modulePackage = basePackage + "." + module + "..";
        String apiPackage = basePackage + "." + module + ".api..";
        return noClasses().that().resideOutsideOfPackage(modulePackage)
                .should().dependOnClassesThat(resideInAPackage(modulePackage).and(resideOutsideOfPackage(apiPackage)))
                .as("classes fora de " + module + " só acessam " + apiPackage)
                .allowEmptyShould(true);
    }

    /**
     * Módulos a proteger: os previstos no plano mais qualquer pasta que exista em {@code modules/},
     * para que um módulo novo não escape da regra por não estar numa lista fixa.
     *
     * @param root raiz de backend-java
     * @return nomes dos módulos, sem repetição (previstos primeiro)
     * @throws IOException se a pasta {@code modules/} existir e não puder ser lida
     */
    public static List<String> discoverModules(Path root) throws IOException {
        Set<String> modules = new LinkedHashSet<>(PLANNED_MODULES);
        Path folder = root.resolve("modules");
        if (Files.isDirectory(folder)) {
            try (Stream<Path> children = Files.list(folder)) {
                children.filter(Files::isDirectory).map(dir -> dir.getFileName().toString()).sorted()
                        .forEach(modules::add);
            }
        }
        return List.copyOf(modules);
    }

    /**
     * Pastas de {@code modules/} com código cujo pacote não segue {@code br.com.kntap.raykookie.<pasta>}.
     * Um módulo assim escaparia da regra 3 em silêncio, então o teste de arquitetura exige lista vazia.
     *
     * @param root raiz de backend-java
     * @return nomes das pastas fora do padrão
     * @throws IOException se a pasta {@code modules/} existir e não puder ser lida
     */
    public static List<String> modulesOutsideTheirPackage(Path root) throws IOException {
        Path folder = root.resolve("modules");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.filter(dir -> Files.isDirectory(dir.resolve("src/main/java")))
                    .filter(dir -> !Files.isDirectory(dir.resolve("src/main/java")
                            .resolve(ArchitectureRules.BASE_PACKAGE.replace('.', '/'))
                            .resolve(dir.getFileName().toString())))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
