package br.com.kntap.raykookie.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Propriedades da leitura e da formatação de dinheiro (5 000 tentativas cada).
 */
class MoneyParsingPropertyTest {

    /**
     * Formatar em reais e ler de volta devolve os mesmos centavos.
     *
     * @param cents valor sorteado entre 0 e 10^12 centavos
     */
    @Property(tries = 5000)
    void formatThenParseRoundTrips(@ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long cents) {

        // ── 1. Formata sem o prefixo da moeda e lê de volta.
        String text = Money.ofCents(cents).toBrl().replace("R$ ", "");
        assertThat(MoneyParser.parse(text).cents()).isEqualTo(cents);
    }

    /**
     * Todo texto com ao menos uma letra é recusado.
     *
     * @param text texto sorteado que contém letra
     */
    @Property(tries = 5000)
    void textWithLettersIsRejected(@ForAll("withLetters") String text) {

        // ── 1. Precisa lançar erro de entrada inválida.
        assertThatThrownBy(() -> MoneyParser.parse(text)).isInstanceOf(InvalidInputException.class);
    }

    /**
     * Gera textos com dígitos, separadores e pelo menos uma letra.
     *
     * @return gerador de textos com letra
     */
    @Provide
    Arbitrary<String> withLetters() {

        // ── 1. Parte numérica qualquer antes e depois de uma letra.
        Arbitrary<String> digits = Arbitraries.strings().withChars("0123456789.,").ofMaxLength(8);
        Arbitrary<Character> letter = Arbitraries.chars().alpha();

        // ── 2. Combina cabeça, letra e cauda.
        return Combinators.combine(digits, letter, digits).as((head, c, tail) -> head + c + tail);
    }
}
