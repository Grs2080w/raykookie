package br.com.kntap.raykookie.identity.adapter.in.web;

/**
 * Resposta de {@code GET /api/me}: só id e usuário, como o Node.
 *
 * @param id       id da conta
 * @param username usuário
 */
public record OwnerView(long id, String username) {
}
