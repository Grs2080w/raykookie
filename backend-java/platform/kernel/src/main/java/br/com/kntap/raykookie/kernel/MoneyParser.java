package br.com.kntap.raykookie.kernel;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Lê valores digitados em pt-BR ({@code "12,5"}, {@code "12.5"}, {@code "1.500,50"}, {@code "1500"}),
 * com a mesma regra de {@code parseLocaleNumber} do backend Node (vetor de paridade em
 * {@code rules-vectors/money-parse.json}), porém mais estrito: recusa vazio, negativo, texto,
 * notação científica, sinal {@code +}, mais de 2 casas decimais e valores que estouram o limite.
 */
public final class MoneyParser {

    /** Ponto seguido de exatamente 3 dígitos, sem vírgula e sem zero à esquerda, é milhar ("1.000" = mil). */
    private static final Pattern THOUSANDS = Pattern.compile("[1-9]\\d{0,2}(\\.\\d{3})+");

    /** Número simples depois da normalização: dígitos com ponto decimal opcional ("5.", ".5" e "0.50"). */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("\\d+\\.?\\d*|\\.\\d+");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private MoneyParser() {
    }

    /**
     * Lê um valor em reais digitado pelo usuário.
     *
     * @param text texto digitado (pode ter espaços nas pontas)
     * @return o valor em centavos
     * @throws InvalidInputException se estiver vazio, não for número, for negativo, tiver mais de 2 casas
     *                               ou não couber em centavos
     */
    public static Money parse(String text) {

        // ── 1. Tira os espaços das pontas (inclusive o não separável e o BOM, como o JavaScript) e recusa vazio.
        String trimmed = trim(text);
        if (trimmed.isEmpty()) {
            throw new InvalidInputException("Informe um valor.");
        }

        // ── 2. Normaliza para o formato com ponto decimal.
        String normalized = normalize(trimmed);

        // ── 3. Só dígitos e um ponto decimal passam; letras, sinais e notação científica caem aqui.
        if (!PLAIN_NUMBER.matcher(normalized).matches()) {
            throw new InvalidInputException("O valor é inválido: use apenas números, como 12,50.");
        }

        // ── 4. Converte com exatidão (Money recusa mais de 2 casas e estouro).
        return Money.fromDecimal(new BigDecimal(normalized));
    }

    /**
     * Converte o texto pt-BR para o formato com ponto decimal, sem separador de milhar.
     *
     * @param text texto sem espaços nas pontas
     * @return texto com ponto decimal (ainda não validado)
     */
    private static String normalize(String text) {
        if (text.contains(",")) {
            return text.replace(".", "").replaceFirst(",", ".");
        }
        if (THOUSANDS.matcher(text).matches()) {
            return text.replace(".", "");
        }
        return text;
    }

    /**
     * Remove espaços das pontas como o {@code trim()} do JavaScript: espaços Unicode, espaço não
     * separável e BOM.
     *
     * @param text texto ou nulo
     * @return texto sem espaços nas pontas; vazio se for nulo
     */
    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /**
     * Diz se o caractere conta como espaço para o corte das pontas.
     *
     * @param c caractere
     * @return verdadeiro para espaços, quebras de linha, espaço não separável e BOM
     */
    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }
}
