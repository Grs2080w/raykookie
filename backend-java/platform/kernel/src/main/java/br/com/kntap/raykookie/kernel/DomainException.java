package br.com.kntap.raykookie.kernel;

/**
 * Erro de regra de negócio: leva uma mensagem em português e o status HTTP que a borda web devolve.
 */
public class DomainException extends RuntimeException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /** Status HTTP correspondente ao erro. */
    private final int httpStatus;

    /**
     * Cria o erro com mensagem e status.
     *
     * @param message    mensagem em português mostrada ao cliente
     * @param httpStatus status HTTP a devolver
     */
    public DomainException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * Status HTTP que a borda web deve devolver para este erro.
     *
     * @return status HTTP
     */
    public int httpStatus() {
        return httpStatus;
    }
}
