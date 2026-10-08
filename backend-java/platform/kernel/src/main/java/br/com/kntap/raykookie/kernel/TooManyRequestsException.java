package br.com.kntap.raykookie.kernel;

/**
 * Limite de tentativas excedido (HTTP 429).
 */
public class TooManyRequestsException extends DomainException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro 429.
     *
     * @param message mensagem em português
     */
    public TooManyRequestsException(String message) {
        super(message, 429);
    }
}
