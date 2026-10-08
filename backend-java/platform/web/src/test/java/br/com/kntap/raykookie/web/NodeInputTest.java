package br.com.kntap.raykookie.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code num} e {@code str} do Node ({@code backend/lib/api.ts}).
 */
class NodeInputTest {

    /**
     * Números: JSON vale como está, texto é lido em pt-BR, vazio vira o padrão.
     */
    @Test
    @DisplayName("num: JSON como está, texto em pt-BR, vazio = padrão")
    void numbers() {
        assertThat(NodeInput.number(12.5)).isEqualTo(12.5);
        assertThat(NodeInput.number(7)).isEqualTo(7);
        assertThat(NodeInput.number(new BigDecimal("0.10"))).isEqualTo(0.1);
        assertThat(NodeInput.number("1.500,50")).isEqualTo(1500.5);
        assertThat(NodeInput.number("1.000")).isEqualTo(1000);
        assertThat(NodeInput.number("0.500")).isEqualTo(0.5);
        assertThat(NodeInput.number("12,5")).isEqualTo(12.5);
        assertThat(NodeInput.number("1e3")).isEqualTo(1000);
        assertThat(NodeInput.number("0x10")).isEqualTo(16);
        assertThat(NodeInput.number("  ", 100)).isEqualTo(100);
        assertThat(NodeInput.number(null, 30)).isEqualTo(30);
        assertThat(NodeInput.number(List.of(5))).isEqualTo(5);
    }

    /**
     * Texto que não é número e negativo: as mensagens do Node.
     */
    @Test
    @DisplayName("num: texto inválido e negativo são recusados com a mensagem do Node")
    void invalidNumbers() {
        assertThatThrownBy(() -> NodeInput.number("doze")).isInstanceOf(InvalidInputException.class)
                .hasMessage("“doze” não é um número válido.");
        assertThatThrownBy(() -> NodeInput.number("x".repeat(30))).hasMessage("“" + "x".repeat(20)
                + "” não é um número válido.");
        assertThatThrownBy(() -> NodeInput.number(-1)).hasMessage("Os valores não podem ser negativos.");
        assertThatThrownBy(() -> NodeInput.number("-0,5")).hasMessage("Os valores não podem ser negativos.");
        assertThatThrownBy(() -> NodeInput.number("Infinity")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> NodeInput.number(true)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> NodeInput.number(Map.of())).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> NodeInput.number("0b2")).isInstanceOf(InvalidInputException.class);
    }

    /**
     * Texto: sem controle, sem espaços nas pontas; números viram texto como no JavaScript.
     */
    @Test
    @DisplayName("str: limpa controles e espaços; número vira texto como no JavaScript")
    void text() {
        assertThat(NodeInput.text(" a\u0000b\u0007 ")).isEqualTo("ab");
        assertThat(NodeInput.text(null)).isEmpty();
        assertThat(NodeInput.text(12.0)).isEqualTo("12");
        assertThat(NodeInput.text(12.5f)).isEqualTo("12.5");
        assertThat(NodeInput.text(7L)).isEqualTo("7");
        assertThat(NodeInput.cut("abcdef", 3)).isEqualTo("abc");
        assertThat(NodeInput.cut("ab", 3)).isEqualTo("ab");
    }

    /**
     * Verdadeiro do JavaScript e ids.
     */
    @Test
    @DisplayName("truthy e ids opcionais")
    void truthyAndIds() {
        assertThat(NodeInput.truthy(null)).isFalse();
        assertThat(NodeInput.truthy(0)).isFalse();
        assertThat(NodeInput.truthy("")).isFalse();
        assertThat(NodeInput.truthy(false)).isFalse();
        assertThat(NodeInput.truthy(Double.NaN)).isFalse();
        assertThat(NodeInput.truthy(List.of())).isTrue();
        assertThat(NodeInput.optionalId(null)).isNull();
        assertThat(NodeInput.optionalId("12")).isEqualTo(12L);
        assertThat(NodeInput.optionalId(3)).isEqualTo(3L);
        assertThatThrownBy(() -> NodeInput.optionalId("1.5")).isInstanceOf(InvalidInputException.class);
        assertThat(NodeInput.positiveId("0o17")).contains(15L);
        assertThat(NodeInput.positiveId("")).isEmpty();
    }
}
