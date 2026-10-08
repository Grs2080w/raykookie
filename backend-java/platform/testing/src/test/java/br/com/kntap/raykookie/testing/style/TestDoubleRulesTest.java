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
 * Prova que {@link TestDoubleRules} recusa dublê de {@code *Repository} nos testes de dinheiro (T04)
 * e deixa em paz os demais módulos e os dublês de outros tipos.
 */
class TestDoubleRulesTest {

    /** Teste de pagamentos que finge o repositório de três jeitos diferentes. */
    private static final String PAYMENTS_TEST_WITH_REPOSITORY_MOCKS = """
            package br.com.kntap.raykookie.payments.application;

            import static org.mockito.Mockito.mock;

            import org.mockito.Mock;
            import org.mockito.Mockito;
            import org.springframework.test.context.bean.override.mockito.MockitoBean;

            class ConfirmPaymentTest {

                @Mock
                PaymentRepository payments;

                @MockitoBean
                OrderRepository orders;

                void prepara() {
                    RefundRepository refunds = Mockito.mock(RefundRepository.class);
                    Clock clock = mock(Clock.class);
                    LedgerRepository ledger = mock(LedgerRepository.class);
                }
            }
            """;

    /** Mesmo dublê de repositório, mas num módulo fora de dinheiro. */
    private static final String CATALOG_TEST_WITH_REPOSITORY_MOCK = """
            package br.com.kntap.raykookie.catalog.application;

            import org.mockito.Mockito;

            class RecipeTest {

                void prepara() {
                    RecipeRepository recipes = Mockito.mock(RecipeRepository.class);
                }
            }
            """;

    /**
     * Cada forma de fingir um repositório num teste de pagamentos vira uma violação; o relógio não.
     */
    @Test
    @DisplayName("dublê de *Repository em teste de pagamentos é recusado")
    void repositoryMocksInMoneyModulesAreReported() {
        List<String> violations = TestDoubleRules.repositoryMocksInSource(
                "ConfirmPaymentTest.java", PAYMENTS_TEST_WITH_REPOSITORY_MOCKS);

        assertThat(violations).containsExactly(
                "ConfirmPaymentTest.java:12 PaymentRepository: dublê de repositório em teste de dinheiro (T04)",
                "ConfirmPaymentTest.java:15 OrderRepository: dublê de repositório em teste de dinheiro (T04)",
                "ConfirmPaymentTest.java:18 RefundRepository: dublê de repositório em teste de dinheiro (T04)",
                "ConfirmPaymentTest.java:20 LedgerRepository: dublê de repositório em teste de dinheiro (T04)");
    }

    /**
     * Fora de pagamentos, devoluções e pedidos a regra não se aplica.
     */
    @Test
    @DisplayName("dublê de *Repository fora dos módulos de dinheiro é permitido")
    void repositoryMocksElsewhereAreAllowed() {
        assertThat(TestDoubleRules.repositoryMocksInSource("RecipeTest.java", CATALOG_TEST_WITH_REPOSITORY_MOCK))
                .isEmpty();
    }

    /**
     * A varredura da raiz olha só {@code src/test/java} e devolve caminhos relativos.
     *
     * @param root pasta temporária que faz o papel de backend-java
     * @throws IOException se a pasta temporária não puder ser escrita
     */
    @Test
    @DisplayName("a varredura cobre src/test/java de todo módulo")
    void scanCoversEveryTestSourceRoot(@TempDir Path root) throws IOException {

        // ── 1. Um teste ruim em refunds e uma cópia igual em produção (que a regra ignora).
        Path test = root.resolve("modules/refunds/src/test/java/x/ConfirmPaymentTest.java");
        Path main = root.resolve("modules/refunds/src/main/java/x/ConfirmPaymentTest.java");
        Files.createDirectories(test.getParent());
        Files.createDirectories(main.getParent());
        Files.writeString(test, PAYMENTS_TEST_WITH_REPOSITORY_MOCKS);
        Files.writeString(main, PAYMENTS_TEST_WITH_REPOSITORY_MOCKS);

        // ── 2. Varre e confere que só o teste aparece.
        assertThat(TestDoubleRules.repositoryMocks(root))
                .hasSize(4)
                .allMatch(violation -> violation.startsWith("modules/refunds/src/test/java/x/ConfirmPaymentTest.java:"));
    }
}
