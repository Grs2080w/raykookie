package br.com.kntap.raykookie.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Regras exatas de dinheiro: tudo em centavos, sem perda de precisão (G12, T08).
 */
class MoneyTest {

    /** Caminho do vetor de paridade gerado pelo TypeScript, copiado para o classpath dos testes. */
    private static final String VECTOR_RESOURCE = "/rules-vectors/money-parse.json";

    /**
     * Soma que em ponto flutuante daria 0.30000000000000004 precisa dar exatamente 30 centavos.
     */
    @Test
    @DisplayName("0,10 + 0,20 é exatamente 0,30")
    void exactSum() {

        // ── 1. Monta os dois valores a partir de texto pt-BR.
        Money a = MoneyParser.parse("0,10");
        Money b = MoneyParser.parse("0,20");

        // ── 2. Soma e confere o centavo exato.
        assertThat(a.plus(b)).isEqualTo(Money.ofCents(30));
    }

    /**
     * Cada linha do vetor gerado pelo TypeScript tem de dar o mesmo resultado no Java (G25).
     *
     * @param input  texto digitado
     * @param cents  centavos que o TypeScript leu, ou nulo se ele recusou
     * @param strict verdadeiro quando o Java é deliberadamente mais estrito que o TypeScript
     */
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @MethodSource("vectors")
    @DisplayName("paridade com o TypeScript na leitura de valores")
    void parityWithTypeScript(String input, Long cents, boolean strict) {

        // ── 1. Entrada inválida no TypeScript, ou mais estrita no Java, precisa ser inválida no Java.
        if (cents == null || strict) {
            assertThatThrownBy(() -> MoneyParser.parse(input)).isInstanceOf(InvalidInputException.class);
            return;
        }

        // ── 2. Entrada válida precisa dar o mesmo número de centavos.
        assertThat(MoneyParser.parse(input).cents()).isEqualTo(cents);
    }

    /**
     * Garante que o vetor tem volume suficiente e inclui as divergências deliberadas.
     */
    @Test
    @DisplayName("o vetor de paridade tem ao menos 60 entradas e marca as divergências")
    void vectorIsBigEnough() {

        // ── 1. Carrega o vetor.
        List<Arguments> rows = vectors().toList();

        // ── 2. Confere volume e a presença de entradas mais estritas no Java.
        assertThat(rows).hasSizeGreaterThanOrEqualTo(60);
        assertThat(rows).anyMatch(row -> (boolean) row.get()[2]);
    }

    /**
     * Valor e arredondamento de texto com várias formas de escrever.
     *
     * @param text  texto digitado
     * @param cents centavos esperados
     */
    @ParameterizedTest(name = "\"{0}\" vira {1} centavos")
    @CsvSource({"'12,5',1250", "'12.5',1250", "'1.500,50',150050", "'1500',150000", "'1.000',100000",
            "'0.500',50", "'5.',500", "'.5',50", "',5',50"})
    @DisplayName("lê as formas aceitas de escrever um valor")
    void parsesAcceptedForms(String text, long cents) {

        // ── 1. Lê o texto e confere o resultado.
        assertThat(MoneyParser.parse(text).cents()).isEqualTo(cents);
    }

    /**
     * Valores fora da regra precisam ser recusados com a mensagem em português.
     *
     * @param text texto recusado
     */
    @ParameterizedTest(name = "\"{0}\" é recusado")
    @CsvSource({"'12,345'", "'-1'", "'abc'", "'   '", "'1e3'", "'99999999999999999999'",
            "'92233720368547758,08'"})
    @DisplayName("recusa vazio, negativo, texto, casas demais e estouro")
    void rejectsInvalid(String text) {

        // ── 1. Lança erro de entrada inválida com status 400.
        assertThatThrownBy(() -> MoneyParser.parse(text))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((DomainException) e).httpStatus()).isEqualTo(400))
                .hasMessageContaining("valor");
    }

    /**
     * Texto nulo é tratado como vazio.
     */
    @Test
    @DisplayName("texto nulo é recusado como vazio")
    void rejectsNull() {

        // ── 1. Nulo não pode virar zero em silêncio.
        assertThatThrownBy(() -> MoneyParser.parse(null))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Informe um valor.");
    }

    /**
     * Conversão de BigDecimal respeita a escala de centavos.
     */
    @Test
    @DisplayName("fromDecimal aceita até 2 casas e toDecimal devolve escala 2")
    void decimalConversion() {

        // ── 1. Ida e volta com escala 2.
        assertThat(Money.fromDecimal(new BigDecimal("12.5")).cents()).isEqualTo(1250);
        assertThat(Money.fromDecimal(new BigDecimal("12.500")).cents()).isEqualTo(1250);
        assertThat(Money.ofCents(1250).toDecimal()).isEqualTo(new BigDecimal("12.50"));
        assertThat(Money.ofCents(1250).toDecimal().scale()).isEqualTo(2);

        // ── 2. Mais de 2 casas significativas, nulo e estouro são recusados.
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("0.001")))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> Money.fromDecimal(null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("1E+30")))
                .isInstanceOf(InvalidInputException.class);
    }

    /**
     * Aritmética e comparações em centavos.
     */
    @Test
    @DisplayName("soma, subtração, multiplicação e comparações")
    void arithmetic() {

        // ── 1. Prepara valores.
        Money ten = Money.ofCents(1000);
        Money three = Money.ofCents(300);

        // ── 2. Operações.
        assertThat(ten.plus(three).cents()).isEqualTo(1300);
        assertThat(ten.minus(three).cents()).isEqualTo(700);
        assertThat(three.times(4).cents()).isEqualTo(1200);
        assertThat(three.minus(ten).cents()).isEqualTo(-700);

        // ── 3. Comparações e zero.
        assertThat(three.isLessThan(ten)).isTrue();
        assertThat(ten.isLessThan(three)).isFalse();
        assertThat(ten.isLessThan(ten)).isFalse();
        assertThat(Money.ofCents(0).isZero()).isTrue();
        assertThat(three.isZero()).isFalse();
        assertThat(Money.ZERO).isEqualTo(Money.ofCents(0));
    }

    /**
     * Estouro de long não pode virar número errado em silêncio.
     */
    @Test
    @DisplayName("estouro aritmético lança erro em vez de dar a volta")
    void overflowFails() {

        // ── 1. Cada operação confere o estouro.
        Money max = Money.ofCents(Long.MAX_VALUE);
        assertThatThrownBy(() -> max.plus(Money.ofCents(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofCents(Long.MIN_VALUE).minus(Money.ofCents(1)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> max.times(2)).isInstanceOf(ArithmeticException.class);
    }

    /**
     * Formatação brasileira com milhar e vírgula, sempre com espaço comum.
     *
     * @param cents    valor em centavos
     * @param expected texto esperado
     */
    @ParameterizedTest(name = "{0} centavos viram {1}")
    @CsvSource(value = {"150050|R$ 1.500,50", "0|R$ 0,00", "5|R$ 0,05", "99|R$ 0,99", "100|R$ 1,00",
            "100000000|R$ 1.000.000,00", "-1250|-R$ 12,50", "123456789|R$ 1.234.567,89"}, delimiter = '|')
    @DisplayName("toBrl formata como R$ 1.500,50 com espaço comum")
    void formatsBrl(long cents, String expected) {

        // ── 1. Formata e confere, inclusive que não há espaço não separável.
        String text = Money.ofCents(cents).toBrl();
        assertThat(text).isEqualTo(expected);
        assertThat(text).doesNotContain(" ").doesNotContain(" ");
    }

    /**
     * Valor mínimo do long também formata.
     */
    @Test
    @DisplayName("toBrl aguenta o menor valor possível")
    void formatsMinimum() {

        // ── 1. Long.MIN_VALUE não tem positivo; precisa formatar mesmo assim.
        assertThat(Money.ofCents(Long.MIN_VALUE).toBrl()).isEqualTo("-R$ 92.233.720.368.547.758,08");
    }

    /**
     * Carrega o vetor JSON gerado pelo TypeScript.
     *
     * @return uma linha por entrada: texto, centavos (nulo se erro) e se o Java é mais estrito
     */
    static Stream<Arguments> vectors() {

        // ── 1. Lê o arquivo do classpath.
        JsonNode root;
        try (InputStream in = MoneyTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Vetor de paridade ilegível", e);
        }

        // ── 2. Converte cada entrada em argumentos de teste.
        return root.valueStream().map(row -> Arguments.of(
                row.get("input").asString(),
                row.has("cents") ? row.get("cents").asLong() : null,
                row.has("java")));
    }
}
