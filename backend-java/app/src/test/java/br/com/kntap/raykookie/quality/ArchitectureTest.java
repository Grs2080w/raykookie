package br.com.kntap.raykookie.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import br.com.kntap.raykookie.testing.architecture.ArchitectureRules;
import br.com.kntap.raykookie.testing.architecture.ModuleRules;
import br.com.kntap.raykookie.testing.style.TestDoubleRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Regras de arquitetura sobre todo o código de produção que o app enxerga (o app depende de todos
 * os módulos). Regras que miram módulos ainda não criados passam vazias e passam a valer sozinhas
 * quando o módulo aparece.
 */
class ArchitectureTest {

    /** Classes de produção do sistema inteiro, importadas uma vez para todas as regras. */
    private static JavaClasses classes;

    /**
     * Importa as classes de produção do pacote base (sem as classes de teste).
     */
    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ArchitectureRules.BASE_PACKAGE);
    }

    /**
     * Regra 1: o domínio não conhece Spring, SQL, adaptadores nem aplicação.
     */
    @Test
    @DisplayName("domínio não depende de Spring, java.sql, adapter nem application")
    void domainIsPure() {
        ArchitectureRules.domainIsPure().check(classes);
    }

    /**
     * Regra 2: a aplicação não conhece adaptadores.
     */
    @Test
    @DisplayName("application não depende de adapter")
    void applicationIgnoresAdapters() {
        ArchitectureRules.applicationIgnoresAdapters().check(classes);
    }

    /**
     * Regra 3: para cada módulo de negócio, quem está fora só usa o pacote {@code api}.
     *
     * @return um teste por módulo (previstos no plano e pastas novas em {@code modules/})
     * @throws IOException se a pasta {@code modules/} não puder ser lida
     */
    @TestFactory
    @DisplayName("fora de cada módulo só se usa o pacote api dele")
    Stream<DynamicTest> onlyApiIsShared() throws IOException {
        Path root = Path.of("..").toRealPath();

        return ModuleRules.discoverModules(root).stream()
                .map(module -> dynamicTest(module, () -> ModuleRules.onlyApiIsShared(module).check(classes)));
    }

    /**
     * Complemento da regra 3: toda pasta de {@code modules/} usa o pacote do próprio nome, senão
     * a regra acima protegeria um pacote vazio e passaria em silêncio.
     *
     * @throws IOException se a pasta {@code modules/} não puder ser lida
     */
    @Test
    @DisplayName("cada pasta de modules/ usa o pacote br.com.kntap.raykookie.<pasta>")
    void moduleFoldersMatchTheirPackages() throws IOException {
        Path root = Path.of("..").toRealPath();

        assertThat(ModuleRules.modulesOutsideTheirPackage(root)).isEmpty();
    }

    /**
     * Regra 4: controllers ficam em {@code adapter.in.web} e toda rota declara {@code @Access}.
     */
    @Test
    @DisplayName("controllers ficam em adapter.in.web e toda rota tem @Access")
    void controllersAreGuarded() {
        ArchitectureRules.controllersLiveInWebAdapters().check(classes);
        ArchitectureRules.mappedEndpointsDeclareAccess().check(classes);
    }

    /**
     * Regra 5: dinheiro nunca passa por {@code double}/{@code float} (G12).
     */
    @Test
    @DisplayName("pagamentos, devoluções, pedidos e kernel não usam double/float")
    void moneyNeverUsesFloatingPoint() {
        ArchitectureRules.moneyNeverUsesFloatingPoint().check(classes);
    }

    /**
     * Regra 6: os pacotes de primeiro nível (módulos) não formam ciclo.
     */
    @Test
    @DisplayName("sem ciclos entre os pacotes de primeiro nível")
    void modulesAreFreeOfCycles() {
        ArchitectureRules.topLevelPackagesAreFreeOfCycles(ArchitectureRules.BASE_PACKAGE).check(classes);
    }

    /**
     * Regra 7: testes de pagamentos, devoluções e pedidos usam banco real, nunca dublê de repositório (T04).
     *
     * @throws IOException se algum fonte de teste não puder ser lido
     */
    @Test
    @DisplayName("testes de dinheiro não fingem *Repository")
    void moneyTestsDoNotMockRepositories() throws IOException {
        Path root = Path.of("..").toRealPath();

        assertThat(TestDoubleRules.repositoryMocks(root)).isEmpty();
    }
}
