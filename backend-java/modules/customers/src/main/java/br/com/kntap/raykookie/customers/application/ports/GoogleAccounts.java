package br.com.kntap.raykookie.customers.application.ports;

import java.net.URI;

/**
 * Porta de saída: login com Google (site, por OAuth; app, por {@code id_token}).
 */
public interface GoogleAccounts {

    /**
     * Diz se o login pelo app está configurado ({@code GOOGLE_CLIENT_ID}).
     *
     * @return verdadeiro se configurado
     */
    boolean appLoginAvailable();

    /**
     * Diz se o login pelo site está configurado ({@code GOOGLE_CLIENT_ID} e {@code GOOGLE_CLIENT_SECRET}).
     *
     * @return verdadeiro se configurado
     */
    boolean webLoginAvailable();

    /**
     * Endereço da tela de login do Google.
     *
     * @param state valor aleatório que volta no callback
     * @return o endereço
     */
    URI authorizationUrl(String state);

    /**
     * Confere um {@code id_token} recebido do app, localmente (assinatura pela JWKS do Google).
     *
     * @param idToken o token
     * @return o perfil
     * @throws br.com.kntap.raykookie.kernel.DomainException 401 com a mensagem do Node se o token não vale
     * @throws GoogleUnavailableException                     se o Google não respondeu
     */
    GoogleProfile profileFromIdToken(String idToken);

    /**
     * Troca o {@code code} do callback por um token e lê o perfil.
     *
     * @param code código devolvido pelo Google
     * @return o perfil
     * @throws GoogleUnavailableException se a troca ou a leitura falhar
     */
    GoogleProfile profileFromCode(String code);

    /**
     * Perfil de uma conta Google.
     *
     * @param sub           id da conta
     * @param email         e-mail (minúsculo)
     * @param name          nome (até 80)
     * @param emailVerified o Google confirmou o e-mail
     */
    record GoogleProfile(String sub, String email, String name, boolean emailVerified) {
    }

    /**
     * O Google não respondeu ou recusou a troca.
     */
    class GoogleUnavailableException extends RuntimeException {

        /** Versão de serialização. */
        private static final long serialVersionUID = 1L;

        /**
         * Cria o erro.
         *
         * @param message o que falhou
         * @param cause   causa
         */
        public GoogleUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
