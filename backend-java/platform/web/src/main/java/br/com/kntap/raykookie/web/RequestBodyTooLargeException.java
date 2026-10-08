package br.com.kntap.raykookie.web;

import java.io.IOException;

/**
 * O corpo da requisição passou do limite da rota enquanto era lido (envio sem {@code Content-Length},
 * ou com um {@code Content-Length} menor do que o corpo de verdade). Vira 413 no {@link GlobalErrorHandler}.
 *
 * <p>É uma {@link IOException} porque é lançada de dentro da leitura do fluxo, como qualquer outro erro
 * de leitura.
 */
public class RequestBodyTooLargeException extends IOException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /**
     * Cria o erro com a mensagem que vai para o cliente.
     *
     * @param message mensagem em português do 413
     */
    public RequestBodyTooLargeException(String message) {
        super(message);
    }
}
