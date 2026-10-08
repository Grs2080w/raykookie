package br.com.kntap.raykookie.kernel;

/**
 * Recurso inexistente (HTTP 404).
 */
public class NotFoundException extends DomainException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro 404.
     *
     * @param message mensagem em português
     */
    public NotFoundException(String message) {
        super(message, 404);
    }
}
