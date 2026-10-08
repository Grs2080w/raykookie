package br.com.kntap.raykookie.payments.api;

import java.util.regex.Pattern;

/**
 * O Mercado Pago recusou a chamada ou não respondeu ({@code MpError}).
 */
public class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Sem saldo na conta do Mercado Pago. */
    private static final Pattern NO_BALANCE = Pattern.compile("(?i)insufficient|amount_exceeds|not_enough");

    /** Prazo de devolução vencido. */
    private static final Pattern EXPIRED = Pattern.compile("(?i)refund_period|period_exceeded|expired");

    /** Status HTTP (0 = sem resposta). */
    private final int status;

    /** Corpo da resposta (ou a mensagem do erro de rede). */
    private final String body;

    /**
     * Cria o erro.
     *
     * @param status status HTTP (0 = sem resposta)
     * @param body   corpo da resposta
     * @param cause  causa (pode ser nula)
     */
    public GatewayException(int status, String body, Throwable cause) {
        super("Mercado Pago " + status + ": " + (body.length() > 300 ? body.substring(0, 300) : body), cause);
        this.status = status;
        this.body = body;
    }

    /**
     * Status HTTP.
     *
     * @return o status (0 = sem resposta)
     */
    public int status() {
        return status;
    }

    /**
     * Corpo da resposta.
     *
     * @return o corpo
     */
    public String body() {
        return body;
    }

    /**
     * Por que a devolução falhou ({@code refundFailReason}).
     *
     * @return {@code saldo}, {@code prazo} ou {@code erro}
     */
    public String refundFailReason() {
        if (status == 428 || NO_BALANCE.matcher(body).find()) {
            return "saldo";
        }
        if (EXPIRED.matcher(body).find() || status == 409 && body.toLowerCase(java.util.Locale.ROOT)
                .contains("period")) {
            return "prazo";
        }
        return "erro";
    }
}
