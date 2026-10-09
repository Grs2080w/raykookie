package br.com.kntap.raykookie.testing.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.architecture.fixtures.payments.MisplacedController;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.in.web.GuardedController;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.in.web.UnguardedController;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.out.persistence.PaymentStore;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.application.LeakyUseCase;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain.BoxedFloatFee;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain.CentsPrice;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain.FloatPrice;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain.LeakyDomain;
import br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain.SpringAwareDomain;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova que cada regra de {@link ArchitectureRules} morde: passa com a classe certa e falha com a
 * classe de exemplo que viola a regra.
 */
class ArchitectureRulesTest {

    /** Pacote base das classes de exemplo usadas pela regra de ciclos. */
    private static final String FIXTURES = "br.com.kntap.raykookie.testing.architecture.fixtures";

    /**
     * Domínio não pode depender de Spring nem da camada de aplicação.
     */
    @Test
    @DisplayName("domínio que usa Spring ou aplicação viola a regra; domínio puro passa")
    void domainMustBePure() {
        ArchRule rule = ArchitectureRules.domainIsPure();

        assertThat(evaluate(rule, CentsPrice.class).hasViolation()).isFalse();
        assertThat(evaluate(rule, SpringAwareDomain.class).hasViolation()).isTrue();
        assertThat(evaluate(rule, LeakyDomain.class, LeakyUseCase.class).hasViolation()).isTrue();
    }

    /**
     * Aplicação não pode depender de adaptador.
     */
    @Test
    @DisplayName("aplicação que usa adaptador viola a regra")
    void applicationMustIgnoreAdapters() {
        ArchRule rule = ArchitectureRules.applicationIgnoresAdapters();

        assertThat(evaluate(rule, LeakyUseCase.class, PaymentStore.class).hasViolation()).isTrue();
        assertThat(evaluate(rule, PaymentStore.class).hasViolation()).isFalse();
    }

    /**
     * Controller fora de {@code adapter.in.web} viola a regra.
     */
    @Test
    @DisplayName("controller fora de adapter.in.web viola a regra")
    void controllersMustLiveInWebAdapters() {
        ArchRule rule = ArchitectureRules.controllersLiveInWebAdapters();

        assertThat(evaluate(rule, GuardedController.class).hasViolation()).isFalse();
        assertThat(evaluate(rule, MisplacedController.class).hasViolation()).isTrue();
    }

    /**
     * Rota mapeada sem {@code @Access} viola a regra; método público sem mapeamento não.
     */
    @Test
    @DisplayName("rota mapeada sem @Access viola a regra")
    void mappedEndpointsMustDeclareAccess() {
        ArchRule rule = ArchitectureRules.mappedEndpointsDeclareAccess();

        assertThat(evaluate(rule, GuardedController.class).hasViolation()).isFalse();
        assertThat(evaluate(rule, UnguardedController.class).hasViolation()).isTrue();
    }

    /**
     * {@code double} em campo e {@code Float} em parâmetro violam a regra de dinheiro.
     */
    @Test
    @DisplayName("double/float em pacote de dinheiro viola a regra")
    void moneyMustNotUseFloatingPoint() {
        ArchRule rule = ArchitectureRules.moneyNeverUsesFloatingPoint();

        assertThat(evaluate(rule, CentsPrice.class).hasViolation()).isFalse();
        assertThat(evaluate(rule, FloatPrice.class).hasViolation()).isTrue();
        assertThat(evaluate(rule, BoxedFloatFee.class).hasViolation()).isTrue();
    }

    /**
     * Dois pacotes que se usam mutuamente formam um ciclo e violam a regra.
     */
    @Test
    @DisplayName("ciclo entre pacotes de primeiro nível viola a regra")
    void topLevelPackagesMustBeFreeOfCycles() {
        ArchRule rule = ArchitectureRules.topLevelPackagesAreFreeOfCycles(FIXTURES + ".cycle");
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES + ".cycle");

        assertThat(rule.evaluate(classes).hasViolation()).isTrue();
    }

    /**
     * Avalia a regra só sobre as classes indicadas.
     *
     * @param rule    regra a avaliar
     * @param classes classes de exemplo
     * @return o resultado da avaliação
     */
    private static EvaluationResult evaluate(ArchRule rule, Class<?>... classes) {
        return rule.evaluate(new ClassFileImporter().importClasses(classes));
    }
}
