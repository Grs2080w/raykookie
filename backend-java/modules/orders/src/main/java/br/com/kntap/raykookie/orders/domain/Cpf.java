package br.com.kntap.raykookie.orders.domain;

/**
 * CPF: só para repassar ao Mercado Pago no pagamento com cartão; nunca é guardado (porte de
 * {@code backend/shared/cpf.ts}).
 */
public final class Cpf {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Cpf() {
    }

    /**
     * Só os dígitos.
     *
     * @param value valor (nulo = vazio)
     * @return os dígitos
     */
    public static String clean(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\D", "");
    }

    /**
     * Dígitos verificadores certos e não é uma sequência repetida.
     *
     * @param value CPF com ou sem pontuação
     * @return verdadeiro se for válido
     */
    public static boolean isValid(Object value) {
        String digits = clean(value);
        if (digits.length() != 11 || digits.matches("(\\d)\\1{10}")) {
            return false;
        }
        return check(digits.substring(0, 9), 10) == digits.charAt(9) - '0'
                && check(digits.substring(0, 10), 11) == digits.charAt(10) - '0';
    }

    /**
     * Um dígito verificador.
     *
     * @param digits dígitos anteriores
     * @param factor fator inicial
     * @return o dígito
     */
    private static int check(String digits, int factor) {
        int sum = 0;
        int weight = factor;
        for (char digit : digits.toCharArray()) {
            sum += (digit - '0') * weight--;
        }
        int rest = sum * 10 % 11;
        return rest == 10 ? 0 : rest;
    }
}
