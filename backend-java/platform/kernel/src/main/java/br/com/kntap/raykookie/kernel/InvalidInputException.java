package br.com.kntap.raykookie.kernel;

/**
 * Entrada recusada por não respeitar as regras (HTTP 400).
 */
public class InvalidInputException extends DomainException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro 400.
     *
     * @param message mensagem em português
     */
    public InvalidInputException(String message) {
        super(message, 400);
    }
}
