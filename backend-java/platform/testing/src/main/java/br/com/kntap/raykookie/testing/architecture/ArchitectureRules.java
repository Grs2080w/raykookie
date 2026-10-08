package br.com.kntap.raykookie.testing.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.metaAnnotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * Regras de arquitetura reutilizáveis (ArchUnit) da Clean Architecture do backend. Toda regra aceita
 * conjunto vazio, porque várias miram módulos que só aparecem em tarefas futuras; quando as classes
 * surgem, a regra passa a valer sem mudança aqui.
 */
public final class ArchitectureRules {

    /** Pacote base de todo o código do backend. */
    public static final String BASE_PACKAGE = "br.com.kntap.raykookie";

    /** Pacotes onde dinheiro circula e, por isso, {@code double}/{@code float} são proibidos (G12). */
    public static final String[] MONEY_PACKAGES = {"..payments..", "..refunds..", "..orders..", "..kernel.."};

    /** Anotações de mapeamento de rota do Spring MVC (todas meta-anotadas com {@code @RequestMapping}). */
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    /** Anotação que marca uma classe como controller REST. */
    private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";

    /** Tipos de ponto flutuante proibidos para dinheiro. */
    private static final Set<String> FLOATING_POINT = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private ArchitectureRules() {
    }

    /**
     * Regra 1: o domínio não depende de Spring, {@code java.sql}, adaptadores nem aplicação.
     *
     * @return a regra
     */
    public static ArchRule domainIsPure() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "java.sql..", "..adapter..", "..application..")
                .as("classes de domínio não dependem de Spring, java.sql, adapter nem application")
                .allowEmptyShould(true);
    }

    /**
     * Regra 2: a camada de aplicação não depende de adaptadores.
     *
     * @return a regra
     */
    public static ArchRule applicationIgnoresAdapters() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .as("classes de application não dependem de adapter")
                .allowEmptyShould(true);
    }

    /**
     * Regra 4a: todo {@code @RestController} fica em {@code adapter.in.web}.
     *
     * @return a regra
     */
    public static ArchRule controllersLiveInWebAdapters() {
        return classes().that().areAnnotatedWith(REST_CONTROLLER)
                .should().resideInAPackage("..adapter.in.web..")
                .as("controllers ficam em ..adapter.in.web..")
                .allowEmptyShould(true);
    }

    /**
     * Regra 4b: todo método público mapeado de um controller declara {@code @Access}.
     *
     * @return a regra
     */
    public static ArchRule mappedEndpointsDeclareAccess() {
        return methods().that().areDeclaredInClassesThat().areAnnotatedWith(REST_CONTROLLER)
                .and().arePublic()
                .and(annotatedWith(REQUEST_MAPPING).or(metaAnnotatedWith(REQUEST_MAPPING)))
                .should(declareAccess())
                .as("toda rota pública de controller declara @Access")
                .allowEmptyShould(true);
    }

    /**
     * Regra 5: nenhum campo, parâmetro ou retorno usa {@code double}/{@code float} nos pacotes de dinheiro.
     *
     * @return a regra
     */
    public static ArchRule moneyNeverUsesFloatingPoint() {
        return classes().that().resideInAnyPackage(MONEY_PACKAGES)
                .should(notUseFloatingPoint())
                .as("pagamentos, devoluções, pedidos e kernel não usam double/float (G12)")
                .allowEmptyShould(true);
    }

    /**
     * Regra 6: os pacotes de primeiro nível abaixo do pacote base não formam ciclo.
     *
     * @param basePackage pacote base (o real é {@link #BASE_PACKAGE})
     * @return a regra
     */
    public static ArchRule topLevelPackagesAreFreeOfCycles(String basePackage) {
        return slices().matching(basePackage + ".(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true);
    }

    /**
     * Condição: o método tem uma anotação de nome simples {@code Access} (a real vem na Tarefa 2.5).
     *
     * @return a condição
     */
    private static ArchCondition<JavaMethod> declareAccess() {
        DescribedPredicate<JavaAnnotation<?>> access = DescribedPredicate.describe("@Access",
                annotation -> annotation.getRawType().getSimpleName().equals("Access"));
        return new ArchCondition<>("declarar @Access") {

            /**
             * Registra o método mapeado que não tem {@code @Access}.
             *
             * @param method método verificado
             * @param events onde o resultado é registrado
             */
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean declared = method.getAnnotations().stream().anyMatch(access);
                String message = method.getFullName() + " é rota e não declara @Access";
                events.add(new SimpleConditionEvent(method, declared, message));
            }
        };
    }

    /**
     * Condição: a classe não tem campo, parâmetro nem retorno {@code double}/{@code float}.
     *
     * @return a condição
     */
    private static ArchCondition<JavaClass> notUseFloatingPoint() {
        return new ArchCondition<>("não usar double/float") {

            /**
             * Registra cada campo, parâmetro ou retorno de ponto flutuante da classe.
             *
             * @param type   classe verificada
             * @param events onde o resultado é registrado
             */
            @Override
            public void check(JavaClass type, ConditionEvents events) {

                // ── 1. Campos (inclusive componentes de record).
                for (JavaField field : type.getFields()) {
                    String name = field.getRawType().getName();
                    String message = field.getFullName() + " é " + name;
                    events.add(new SimpleConditionEvent(field, !FLOATING_POINT.contains(name), message));
                }

                // ── 2. Parâmetros e retornos de métodos e construtores.
                for (JavaCodeUnit unit : type.getCodeUnits()) {
                    unit.getRawParameterTypes().stream().map(JavaClass::getName).filter(FLOATING_POINT::contains)
                            .forEach(name -> events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " recebe " + name)));
                    String returned = unit.getRawReturnType().getName();
                    if (FLOATING_POINT.contains(returned)) {
                        events.add(SimpleConditionEvent.violated(unit, unit.getFullName() + " devolve " + returned));
                    }
                }
            }
        };
    }
}
