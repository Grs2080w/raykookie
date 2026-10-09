package br.com.kntap.raykookie.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Teste de um comportamento que o Java muda de propósito (um gap G0x): roda só com
 * {@code CONTRACT_TARGET=java}. Contra o Node ele falharia por definição.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ChangedFromNode.OnlyOnJava.class)
public @interface ChangedFromNode {

    /**
     * O gap que justifica a mudança.
     *
     * @return o id (ex.: {@code G01})
     */
    String value();

    /**
     * Liga o teste só quando o alvo é o Java.
     */
    final class OnlyOnJava implements ExecutionCondition {

        /**
         * Decide se o teste roda.
         *
         * @param context contexto do JUnit
         * @return habilitado só com o alvo Java
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (ContractTarget.fromEnvironment().isJava()) {
                return ConditionEvaluationResult.enabled("alvo Java");
            }
            return ConditionEvaluationResult.disabled("comportamento mudado de propósito; só vale no Java");
        }
    }
}
