package br.com.kntap.raykookie.testing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.architecture.fixtures.outsider.BadClient;
import br.com.kntap.raykookie.testing.architecture.fixtures.outsider.GoodClient;
import br.com.kntap.raykookie.testing.architecture.fixtures.shop.api.ShopApi;
import br.com.kntap.raykookie.testing.architecture.fixtures.shop.internal.ShopSecret;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prova que {@link ModuleRules} só deixa outros módulos usarem o pacote {@code api} e que a lista
 * de módulos inclui os que aparecem em {@code modules/}.
 */
class ModuleRulesTest {

    /** Pacote base das classes de exemplo. */
    private static final String FIXTURES = "br.com.kntap.raykookie.testing.architecture.fixtures";

    /**
     * Usar a API do módulo é permitido; usar uma classe interna não.
     */
    @Test
    @DisplayName("classe de fora que usa pacote interno do módulo viola a regra")
    void onlyApiIsVisibleOutsideTheModule() {
        ArchRule rule = ModuleRules.onlyApiIsShared(FIXTURES, "shop");

        assertThat(rule.evaluate(new ClassFileImporter()
                .importClasses(GoodClient.class, ShopApi.class, ShopSecret.class)).hasViolation()).isFalse();
        assertThat(rule.evaluate(new ClassFileImporter()
                .importClasses(BadClient.class, ShopApi.class, ShopSecret.class)).hasViolation()).isTrue();
    }

    /**
     * A regra do pacote base real cita o módulo pedido.
     */
    @Test
    @DisplayName("a regra do pacote real descreve o módulo protegido")
    void ruleForRealBasePackageNamesTheModule() {
        assertThat(ModuleRules.onlyApiIsShared("payments").getDescription())
                .contains("br.com.kntap.raykookie.payments.api");
    }

    /**
     * A lista de módulos junta os previstos no plano com qualquer pasta nova em {@code modules/}.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("módulos previstos e pastas novas em modules/ entram na regra")
    void discoversPlannedAndNewModules(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("modules/loyalty/src/main/java"));
        Files.createDirectories(root.resolve("modules/payments/src/main/java"));

        assertThat(ModuleRules.discoverModules(root))
                .contains("loyalty", "payments", "identity", "releases")
                .doesNotHaveDuplicates();
    }

    /**
     * Sem a pasta {@code modules/}, a lista é a dos módulos previstos.
     *
     * @param root pasta temporária vazia
     * @throws IOException se a pasta não puder ser lida
     */
    @Test
    @DisplayName("sem modules/ a lista é a dos 12 módulos previstos")
    void withoutModulesFolderUsesPlannedModules(@TempDir Path root) throws IOException {
        assertThat(ModuleRules.discoverModules(root)).containsExactlyElementsOf(ModuleRules.PLANNED_MODULES);
    }

    /**
     * Pasta de módulo cujo código está em outro pacote é apontada; a que segue o padrão não.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("pasta de módulo com pacote diferente do nome é apontada")
    void moduleFolderMustMatchItsPackage(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("modules/orders/src/main/java/br/com/kntap/raykookie/orders"));
        Files.createDirectories(root.resolve("modules/refunds/src/main/java/br/com/kntap/raykookie/refund"));

        assertThat(ModuleRules.modulesOutsideTheirPackage(root)).containsExactly("refunds");
    }
}
