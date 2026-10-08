package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.DomainException;

/**
 * Sem sessão válida para a rota (HTTP 401).
 */
public class UnauthorizedException extends DomainException {

    /** Versão de serialização. */
    private static final long serialVersionUID = 1L;

    /** Mensagem das rotas da dona: a mesma do Node ({@code backend/lib/api.ts}). */
    public static final String OWNER_MESSAGE = "Sessão expirada. Entre novamente.";

    /** Mensagem das rotas do cliente: a mesma do Node ({@code backend/lib/customer-api.ts}). */
    public static final String CUSTOMER_MESSAGE = "Sua sessão expirou. Entre de novo para continuar.";

    /**
     * Cria o erro 401.
     *
     * @param message mensagem em português
     */
    public UnauthorizedException(String message) {
        super(message, 401);
    }
}
