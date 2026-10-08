package br.com.kntap.raykookie.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code validateFeedback}: os casos de {@code backend/tests/feedback.test.ts}.
 */
class FeedbackRulesTest {

    /**
     * Nota de 1 a 5.
     */
    @Test
    @DisplayName("aceita nota de 1 a 5 com mensagem dentro do limite")
    void accepts() {
        for (int r = 1; r <= 5; r++) {
            assertThat(FeedbackRules.validate(r, "Muito bom!")).isEmpty();
        }
    }

    /**
     * Nota inválida.
     */
    @Test
    @DisplayName("recusa nota fora de 1-5 ou não inteira")
    void badRating() {
        assertThat(FeedbackRules.validate(0, "")).get().asString().contains("nota");
        assertThat(FeedbackRules.validate(6, "")).get().asString().contains("nota");
        assertThat(FeedbackRules.validate(3.5, "")).get().asString().contains("nota");
        assertThat(FeedbackRules.validate("5", "")).get().asString().contains("nota");
        assertThat(FeedbackRules.validate(Double.NaN, "")).get().asString().contains("nota");
        assertThat(FeedbackRules.validate(4.0, "")).isEmpty();
    }

    /**
     * Mensagem que não é texto.
     */
    @Test
    @DisplayName("recusa mensagem que não é string")
    void badMessage() {
        assertThat(FeedbackRules.validate(5, null)).get().asString().containsIgnoringCase("mensagem");
        assertThat(FeedbackRules.validate(5, 123)).get().asString().containsIgnoringCase("mensagem");
    }

    /**
     * Limite da mensagem.
     */
    @Test
    @DisplayName("recusa mensagem acima do limite")
    void longMessage() {
        assertThat(FeedbackRules.validate(5, "a".repeat(FeedbackRules.MAX_MESSAGE))).isEmpty();
        assertThat(FeedbackRules.validate(5, "a".repeat(FeedbackRules.MAX_MESSAGE + 1))).get().asString()
                .contains("máximo");
    }

    /**
     * Mensagem vazia.
     */
    @Test
    @DisplayName("mensagem vazia é válida (só a nota é obrigatória)")
    void emptyMessage() {
        assertThat(FeedbackRules.validate(4, "")).isEmpty();
    }
}
