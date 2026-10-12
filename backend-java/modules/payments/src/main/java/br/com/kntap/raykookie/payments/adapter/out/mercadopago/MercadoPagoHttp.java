package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Chamadas HTTP à API do Mercado Pago usadas pelo adaptador da API Orders: tempo limite de conexão de 3 s e de
 * leitura de 10 s; erro HTTP e falha de rede viram {@link GatewayException}.
 */
@Component
class MercadoPagoHttp {

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Cliente HTTP. */
    private final RestClient http;

    /** Configuração (token de acesso). */
    private final PaymentSettings settings;

    /**
     * Cria o cliente.
     *
     * @param apiBase  {@code MP_API_BASE}
     * @param settings configuração
     */
    MercadoPagoHttp(@Value("${rayka.mp.api-base:https://api.mercadopago.com}") String apiBase,
            PaymentSettings settings) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(MercadoPagoClient.CONNECT_TIMEOUT).build());
        factory.setReadTimeout(MercadoPagoClient.READ_TIMEOUT);
        this.http = RestClient.builder().baseUrl(apiBase).requestFactory(factory).build();
        this.settings = settings;
    }

    /**
     * Faz uma chamada e devolve o JSON da resposta.
     *
     * @param method         método HTTP
     * @param path           caminho (pode ter {@code {variáveis}})
     * @param body           corpo (nulo = sem corpo)
     * @param idempotencyKey {@code X-Idempotency-Key} (nulo = sem a chave)
     * @param headers        cabeçalhos extras
     * @param variables      valores das variáveis do caminho
     * @return o JSON (objeto vazio se a resposta veio sem corpo)
     * @throws GatewayException se o Mercado Pago respondeu erro ou não respondeu
     */
    JsonNode call(HttpMethod method, String path, Object body, String idempotencyKey, Map<String, String> headers,
            Object... variables) {

        // ── 1. Requisição com o token, a chave de idempotência e os cabeçalhos extras.
        RestClient.RequestBodySpec request = http.method(method).uri(path, variables)
                .header("Authorization", "Bearer " + settings.accessToken()).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("X-Idempotency-Key", idempotencyKey);
        }
        headers.forEach(request::header);
        if (body != null) {
            request.body(JSON.writeValueAsString(body));
        }

        // ── 2. Resposta: erro HTTP e falha de rede viram GatewayException.
        try {
            return request.exchange((req, res) -> {
                String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new GatewayException(res.getStatusCode().value(), text, null);
                }
                return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            });
        } catch (ResourceAccessException e) {
            throw new GatewayException(0, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Interpreta o corpo de uma resposta de erro como JSON.
     *
     * @param text corpo
     * @return o JSON, ou objeto vazio se não for JSON
     */
    static JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (tools.jackson.core.JacksonException e) {
            return JSON.createObjectNode();
        }
    }
}
