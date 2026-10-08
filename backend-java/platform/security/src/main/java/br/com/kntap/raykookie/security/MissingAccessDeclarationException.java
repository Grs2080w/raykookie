package br.com.kntap.raykookie.security;

/**
 * Uma rota foi chamada sem declarar {@link Access}. É erro de programação: vira 500 com a mensagem
 * genérica (nunca abre a rota).
 */
public class MissingAccessDeclarationException extends RuntimeException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro.
     *
     * @param route assinatura do método da rota
     */
    public MissingAccessDeclarationException(String route) {
        super("Rota sem @Access: " + route);
    }
}
