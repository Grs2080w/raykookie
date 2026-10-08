package br.com.kntap.raykookie.observability;

import java.util.Locale;

/**
 * Tipos de alerta operacional (a etiqueta {@code kind} da métrica {@code rayka_alerts_total}).
 */
public enum AlertKind {

    /** Problema com dinheiro entrando: pagamento recusado de forma inesperada, duplicado, sem pedido. */
    PAYMENT,

    /** Devolução que falhou no gateway. */
    REFUND,

    /** Tarefa agendada que falhou. */
    JOB,

    /** Integração externa fora do ar (Mercado Pago, Firebase, Google). */
    INTEGRATION;

    /**
     * Nome do tipo em minúsculas, como vai na métrica e no webhook.
     *
     * @return o nome
     */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
