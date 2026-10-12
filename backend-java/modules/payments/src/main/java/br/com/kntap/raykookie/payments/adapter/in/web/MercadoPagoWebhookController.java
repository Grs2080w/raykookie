package br.com.kntap.raykookie.payments.adapter.in.web;

import br.com.kntap.raykookie.payments.application.WebhookUseCase;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notificações do Mercado Pago (URL pública, sem sessão: a assinatura é conferida pelo caso de uso).
 */
@RestController
public class MercadoPagoWebhookController {

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Caso de uso. */
    private final WebhookUseCase webhook;

    /**
     * Cria o controller.
     *
     * @param webhook caso de uso
     */
    public MercadoPagoWebhookController(WebhookUseCase webhook) {
        this.webhook = webhook;
    }

    /**
     * Recebe a notificação: {@code type} e {@code data.id} do corpo ou da query ({@code topic}/{@code id}).
     *
     * @param raw       corpo (JSON inválido conta como vazio)
     * @param query     parâmetros da URL
     * @param signature {@code x-signature}
     * @param requestId {@code x-request-id}
     * @return a resposta
     */
    @Access(AccessLevel.SIGNED_WEBHOOK)
    @PostMapping("/api/webhooks/mercadopago")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody(required = false) String raw,
            @RequestParam Map<String, String> query, @RequestHeader(name = "x-signature", required = false)
            String signature, @RequestHeader(name = "x-request-id", required = false) String requestId) {
        JsonNode body = parse(raw);
        String type = first(body.path("type"), query.get("type"), query.get("topic"));
        String dataId = first(body.path("data").path("id"), query.get("data.id"), query.get("id"));
        WebhookUseCase.Reply reply = webhook.handle(new WebhookUseCase.Notification(type, dataId, signature,
                requestId, simpleFields(body.path("data"))));
        return ResponseEntity.status(reply.status()).body(reply.body());
    }

    /**
     * Campos simples (texto ou número) de {@code data}, como texto. Os alertas de fraude trazem aí o pagamento e o
     * pedido do comerciante.
     *
     * @param data o nó {@code data} do corpo
     * @return os campos (vazio se não houver)
     */
    private static Map<String, String> simpleFields(JsonNode data) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (data.isObject()) {
            data.propertyNames().forEach(name -> {
                JsonNode value = data.get(name);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    fields.put(name, value.asString());
                }
            });
        }
        return fields;
    }

    /**
     * JSON do corpo, ou vazio.
     *
     * @param raw corpo
     * @return o JSON
     */
    private static JsonNode parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (JacksonException e) {
            return JSON.createObjectNode();
        }
    }

    /**
     * Primeiro valor presente ({@code a ?? b ?? c ?? ''}).
     *
     * @param node      campo do corpo
     * @param fallback  primeira alternativa
     * @param fallback2 segunda alternativa
     * @return o texto
     */
    private static String first(JsonNode node, String fallback, String fallback2) {
        if (!node.isMissingNode() && !node.isNull()) {
            return node.asString();
        }
        return fallback != null ? fallback : fallback2 != null ? fallback2 : "";
    }
}
