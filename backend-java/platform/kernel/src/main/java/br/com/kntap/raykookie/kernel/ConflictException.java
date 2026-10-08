package br.com.kntap.raykookie.kernel;

/**
 * Conflito com o estado atual do recurso (HTTP 409).
 */
public class ConflictException extends DomainException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro 409.
     *
     * @param message mensagem em português
     */
    public ConflictException(String message) {
        super(message, 409);
    }
}
