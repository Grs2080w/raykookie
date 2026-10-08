package br.com.kntap.raykookie.identity.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.util.regex.Pattern;

/**
 * Regras de usuário e senha da dona, com as mensagens do Node ({@code backend/app/api/auth/register}).
 */
public final class Credentials {

    /** Senha mais longa aceita: o scrypt de uma senha enorme custaria caro a cada tentativa. */
    public static final int MAX_PASSWORD = 200;

    /** Senha mais curta aceita no cadastro. */
    public static final int MIN_PASSWORD = 8;

    /** Usuário: 3 a 32 letras (de qualquer língua), números, ponto, hífen ou sublinhado. */
    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_.-]{3,32}$");

    /**
     * Sem instâncias.
     */
    private Credentials() {
    }

    /**
     * Confere o usuário de um cadastro.
     *
     * @param username usuário já sem espaços nas pontas
     * @return o próprio usuário
     * @throws InvalidInputException se não seguir a regra
     */
    public static String requireValidUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new InvalidInputException("Use de 3 a 32 caracteres: letras, números, ponto, hífen ou sublinhado.");
        }
        return username;
    }

    /**
     * Confere a senha de um cadastro.
     *
     * @param password senha
     * @throws InvalidInputException se for curta ou longa demais
     */
    public static void requireValidNewPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new InvalidInputException("A senha precisa ter pelo menos 8 caracteres.");
        }
        requireNotTooLong(password);
    }

    /**
     * Recusa senha longa demais antes de qualquer scrypt (login e cadastro).
     *
     * @param password senha
     * @throws InvalidInputException se passar de {@link #MAX_PASSWORD} caracteres
     */
    public static void requireNotTooLong(String password) {
        if (password != null && password.length() > MAX_PASSWORD) {
            throw new InvalidInputException("A senha pode ter no máximo 200 caracteres.");
        }
    }
}
