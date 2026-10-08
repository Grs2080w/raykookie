package br.com.kntap.raykookie.observability;

import java.util.Map;

/**
 * Um alerta, como vai para o webhook ({@code ALERT_WEBHOOK_URL}).
 *
 * @param kind      tipo
 * @param message   o que aconteceu, em português
 * @param context   dados para investigar (ids, valores; nunca segredos)
 * @param requestId requisição em que aconteceu ({@code -} fora de uma requisição)
 */
public record Alert(String kind, String message, Map<String, Object> context, String requestId) {
}
