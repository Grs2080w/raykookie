package br.com.kntap.raykookie.kernel;

import java.math.BigDecimal;

/**
 * Dinheiro em centavos inteiros: sem ponto flutuante, sem perda de precisão (G12).
 * {@link BigDecimal} só aparece na borda (colunas {@code NUMERIC(…,2)}).
 *
 * @param cents valor em centavos (pode ser negativo em resultados de subtração)
 */
public record Money(long cents) {

    /** Valor zero. */
    public static final Money ZERO = new Money(0);

    /** Quantidade de casas decimais de um valor em reais. */
    private static final int SCALE = 2;

    /**
     * Cria o valor a partir de centavos.
     *
     * @param cents quantidade de centavos
     * @return o valor
     */
    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /**
     * Converte um decimal em reais; aceita no máximo 2 casas significativas ({@code 12.500} vale 12,50).
     *
     * @param value valor em reais
     * @return o valor em centavos
     * @throws InvalidInputException se for nulo, tiver mais de 2 casas ou não couber em centavos
     */
    public static Money fromDecimal(BigDecimal value) {

        // ── 1. Nulo não vira zero em silêncio.
        if (value == null) {
            throw new InvalidInputException("Informe um valor.");
        }

        // ── 2. Zeros à direita não contam como casa decimal.
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() > SCALE) {
            throw new InvalidInputException("O valor aceita no máximo 2 casas decimais.");
        }

        // ── 3. Converte para centavos, recusando o que estoura o limite.
        try {
            return new Money(normalized.movePointRight(SCALE).longValueExact());
        } catch (ArithmeticException e) {
            throw new InvalidInputException("O valor é grande demais.");
        }
    }

    /**
     * Valor em reais com escala 2, para gravar em colunas {@code NUMERIC(…,2)}.
     *
     * @return decimal em reais com duas casas
     */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(cents, SCALE);
    }

    /**
     * Soma exata.
     *
     * @param other outro valor
     * @return a soma
     * @throws ArithmeticException se estourar o limite de {@code long}
     */
    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    /**
     * Subtração exata.
     *
     * @param other valor a subtrair
     * @return a diferença
     * @throws ArithmeticException se estourar o limite de {@code long}
     */
    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    /**
     * Multiplicação exata por uma quantidade.
     *
     * @param quantity quantidade inteira
     * @return o valor multiplicado
     * @throws ArithmeticException se estourar o limite de {@code long}
     */
    public Money times(long quantity) {
        return new Money(Math.multiplyExact(cents, quantity));
    }

    /**
     * Compara com outro valor.
     *
     * @param other valor de comparação
     * @return verdadeiro se este valor é estritamente menor
     */
    public boolean isLessThan(Money other) {
        return cents < other.cents;
    }

    /**
     * Informa se o valor é zero.
     *
     * @return verdadeiro quando são 0 centavos
     */
    public boolean isZero() {
        return cents == 0;
    }

    /**
     * Formata como moeda brasileira, com milhar em ponto e vírgula decimal ({@code R$ 1.500,50}),
     * sempre com espaço comum (nunca o espaço não separável que alguns formatadores do JDK usam).
     * Negativos saem como {@code -R$ 12,50}.
     *
     * @return texto em reais
     */
    public String toBrl() {

        // ── 1. Separa o sinal dos dígitos (evita Math.abs, que falha em Long.MIN_VALUE).
        String digits = Long.toString(cents);
        boolean negative = digits.startsWith("-");
        if (negative) {
            digits = digits.substring(1);
        }

        // ── 2. Garante ao menos 3 dígitos (reais + 2 de centavos) e separa as partes.
        digits = "0".repeat(Math.max(0, SCALE + 1 - digits.length())) + digits;
        String reais = digits.substring(0, digits.length() - SCALE);
        String centavos = digits.substring(digits.length() - SCALE);

        // ── 3. Monta o texto com o agrupamento brasileiro.
        return (negative ? "-" : "") + "R$ " + groupThousands(reais) + "," + centavos;
    }

    /**
     * Insere ponto a cada três dígitos, da direita para a esquerda.
     *
     * @param integerDigits dígitos da parte inteira, sem sinal
     * @return parte inteira com pontos de milhar
     */
    private static String groupThousands(String integerDigits) {
        StringBuilder out = new StringBuilder();
        int firstGroup = integerDigits.length() % 3;
        if (firstGroup > 0) {
            out.append(integerDigits, 0, firstGroup);
        }
        for (int i = firstGroup; i < integerDigits.length(); i += 3) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(integerDigits, i, i + 3);
        }
        return out.toString();
    }
}
