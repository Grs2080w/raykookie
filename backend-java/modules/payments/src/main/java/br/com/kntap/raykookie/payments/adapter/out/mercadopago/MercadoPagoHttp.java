package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mercadopago.client.order.OrderClient;
import com.mercadopago.client.paymentmethod.PaymentMethodClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPHttpClient;
import com.mercadopago.net.MPRequest;
import com.mercadopago.net.MPResource;
import com.mercadopago.net.MPResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ponte com o SDK oficial do Mercado Pago para Java ({@code com.mercadopago:sdk-java}): entrega o
 * {@link OrderClient} do SDK, as opções de cada chamada (token, chave de idempotência, tempos limite e cabeçalhos
 * extras) e a resposta já como JSON. Tempo limite de conexão de 3 s e de leitura de 10 s; erro HTTP e falha de rede
 * viram {@link GatewayException}.
 */
@Component
class MercadoPagoHttp {

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Endereço padrão da API, que o SDK usa em toda requisição (o {@code MP_API_BASE} o substitui). */
    private static final String SDK_BASE = "https://api.mercadopago.com";

    /** Cliente de pedidos do SDK. */
    private final OrderClient orders;

    /** Cliente de meios de pagamento do SDK. */
    private final PaymentMethodClient paymentMethods;

    /** Configuração (token de acesso). */
    private final PaymentSettings settings;

    /**
     * Cria a ponte.
     *
     * @param apiBase  {@code MP_API_BASE}
     * @param settings configuração
     */
    MercadoPagoHttp(@Value("${rayka.mp.api-base:https://api.mercadopago.com}") String apiBase,
            PaymentSettings settings) {
        MPHttpClient client = new RebasedHttpClient(sdkClient(apiBase), apiBase);
        this.orders = new OrderClient(client);
        this.paymentMethods = new PaymentMethodClient(client);
        this.settings = settings;
    }

    /**
     * Clientes do SDK.
     *
     * @param orders         pedidos
     * @param paymentMethods meios de pagamento
     */
    record Sdk(OrderClient orders, PaymentMethodClient paymentMethods) {
    }

    /** Uma chamada ao SDK, que devolve o recurso da resposta. */
    @FunctionalInterface
    interface Operation {

        /**
         * Executa a chamada.
         *
         * @param sdk     clientes do SDK
         * @param options token, chave de idempotência, tempos limite e cabeçalhos
         * @return o recurso da resposta
         * @throws MPException    se o SDK não conseguiu falar com o Mercado Pago
         * @throws MPApiException se o Mercado Pago respondeu erro
         */
        MPResource run(Sdk sdk, MPRequestOptions options) throws MPException, MPApiException;
    }

    /**
     * Faz uma chamada pelo SDK e devolve o JSON da resposta.
     *
     * @param operation      a chamada ao SDK
     * @param idempotencyKey {@code X-Idempotency-Key} (nulo = a que o SDK gera)
     * @param headers        cabeçalhos extras
     * @return o JSON (objeto vazio se a resposta veio sem corpo)
     * @throws GatewayException se o Mercado Pago respondeu erro ou não respondeu
     */
    JsonNode call(Operation operation, String idempotencyKey, Map<String, String> headers) {

        // ── 1. Opções da chamada: token, tempos limite, chave de idempotência e cabeçalhos extras.
        Map<String, String> custom = new LinkedHashMap<>(headers);
        if (idempotencyKey != null) {
            custom.put("X-Idempotency-Key", idempotencyKey);
        }
        MPRequestOptions options = MPRequestOptions.builder().accessToken(settings.accessToken())
                .connectionTimeout((int) MercadoPagoClient.CONNECT_TIMEOUT.toMillis())
                .socketTimeout((int) MercadoPagoClient.READ_TIMEOUT.toMillis()).customHeaders(custom).build();

        // ── 2. Resposta: erro HTTP e falha de rede viram GatewayException.
        try {
            return parse(operation.run(new Sdk(orders, paymentMethods), options).getResponse().getContent());
        } catch (MPApiException e) {
            MPResponse response = e.getApiResponse();
            boolean empty = response == null || response.getContent() == null || response.getContent().isBlank();
            throw new GatewayException(e.getStatusCode(), empty ? String.valueOf(e.getMessage())
                    : response.getContent(), e);
        } catch (MPException e) {
            throw new GatewayException(0, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Cliente HTTP do SDK. O padrão só fala HTTPS (o da produção); um {@code MP_API_BASE} em {@code http://} (um
     * Mercado Pago falso, em teste ou desenvolvimento) usa um cliente Apache comum.
     *
     * @param apiBase {@code MP_API_BASE}
     * @return o cliente
     */
    private static MPHttpClient sdkClient(String apiBase) {
        return apiBase.startsWith("http://") ? new PlainHttpClient() : new MPDefaultHttpClient();
    }

    /** Cliente do SDK sobre HTTP simples, só para o Mercado Pago falso. */
    private static final class PlainHttpClient extends MPDefaultHttpClient {

        /** Cria o cliente. */
        PlainHttpClient() {
            super(HttpClientBuilder.create().build());
        }
    }

    /**
     * Interpreta o corpo de uma resposta como JSON.
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

    /**
     * Cliente HTTP do SDK com dois ajustes: troca o endereço padrão da API pelo configurado em {@code MP_API_BASE} (o
     * SDK fixa {@code https://api.mercadopago.com}; em produção o endereço é o mesmo) e manda o
     * {@code additional_info} como a API Orders o aceita, com chaves pontuadas.
     */
    private static final class RebasedHttpClient implements MPHttpClient {

        /** Cliente HTTP padrão do SDK. */
        private final MPHttpClient delegate;

        /** Endereço base a usar no lugar do padrão. */
        private final String base;

        /**
         * Cria o cliente.
         *
         * @param delegate cliente padrão do SDK
         * @param base     endereço base
         */
        RebasedHttpClient(MPHttpClient delegate, String base) {
            this.delegate = delegate;
            this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        }

        /** {@inheritDoc} */
        @Override
        public MPResponse send(MPRequest request) throws MPException, MPApiException {
            JsonObject payload = flattenAdditionalInfo(request.getPayload());
            String uri = request.getUri();
            boolean rebase = uri.startsWith(SDK_BASE) && !SDK_BASE.equals(base);
            if (payload == request.getPayload() && !rebase) {
                return delegate.send(request);
            }
            return delegate.send(MPRequest.builder().uri(rebase ? base + uri.substring(SDK_BASE.length()) : uri)
                    .method(request.getMethod()).headers(request.getHeaders()).payload(payload)
                    .queryParams(request.getQueryParams()).accessToken(request.getAccessToken())
                    .connectionTimeout(request.getConnectionTimeout())
                    .connectionRequestTimeout(request.getConnectionRequestTimeout())
                    .socketTimeout(request.getSocketTimeout()).build());
        }
    }

    /**
     * A API Orders só aceita os campos de {@code additional_info} com a chave pontuada
     * ({@code {"payer.registration_date":"…"}}) e recusa o formato aninhado que o SDK gera
     * ({@code {"payer":{"registration_date":"…"}}}, 400 {@code additionalProperties 'payer' not allowed}), o que foi
     * verificado no sandbox. Aqui o aninhado vira pontuado.
     *
     * @param payload corpo da requisição (pode ser nulo)
     * @return o mesmo objeto se não há {@code additional_info}; senão, uma cópia com ele achatado
     */
    static JsonObject flattenAdditionalInfo(JsonObject payload) {
        if (payload == null || !payload.has("additional_info") || !payload.get("additional_info").isJsonObject()) {
            return payload;
        }
        JsonObject copy = payload.deepCopy();
        JsonObject flat = new JsonObject();
        flatten("", copy.getAsJsonObject("additional_info"), flat);
        copy.add("additional_info", flat);
        return copy;
    }

    /**
     * Copia as folhas de {@code source} para {@code target}, com o caminho como chave.
     *
     * @param prefix caminho até aqui, com ponto no fim (vazio na raiz)
     * @param source objeto aninhado
     * @param target objeto achatado
     */
    private static void flatten(String prefix, JsonObject source, JsonObject target) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                flatten(prefix + entry.getKey() + ".", entry.getValue().getAsJsonObject(), target);
            } else {
                target.add(prefix + entry.getKey(), entry.getValue());
            }
        }
    }
}
