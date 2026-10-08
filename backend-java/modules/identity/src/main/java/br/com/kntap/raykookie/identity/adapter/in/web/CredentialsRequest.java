package br.com.kntap.raykookie.identity.adapter.in.web;

/**
 * Corpo de {@code POST /api/auth/login} e {@code POST /api/auth/register}. Campos ausentes valem como
 * vazios, como no Node ({@code String(b.username ?? '')}).
 *
 * @param username usuário
 * @param password senha
 */
public record CredentialsRequest(String username, String password) {
}
