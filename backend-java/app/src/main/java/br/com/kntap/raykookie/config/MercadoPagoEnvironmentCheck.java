package br.com.kntap.raykookie.config;

import br.com.kntap.raykookie.observability.OpsAlert;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confere na subida as credenciais do cartão (G31). Em produção, em 2026-10-07, faltava a
 * {@code MP_PUBLIC_KEY}: o cartão respondia 503 e ninguém percebia. Agora:
 *
 * <ol>
 *   <li>o access token precisa ser aceito ({@code GET /users/me} → 200) e, em produção, não pode ser de
 *       uma conta de teste ({@code tags} com {@code test_user}; ver o diagnóstico da Tarefa 0.3);</li>
 *   <li>a chave pública precisa ser aceita
 *       ({@code GET /v1/payment_methods/installments?bin=503143&amount=10&public_key=…} → 200).</li>
 * </ol>
 *
 * <p>Conferir se os dois são da mesma conta ficou de fora: a Tarefa 0.3 não achou um jeito documentado de
 * saber a conta de uma chave pública sem criar um token de cartão, e isso fica para a suíte ao vivo.
 *
 * <p>Qualquer falha deixa o cartão desligado ({@link CardPaymentsStatus}) e dispara
 * {@code OpsAlert.integrationDown("mercadopago-cartao", …)}; o servidor sobe mesmo assim (Pix e o resto
 * continuam funcionando).
 */
@Component
public class MercadoPagoEnvironmentCheck implements ApplicationRunner {

    /** Nome da integração nos alertas. */
    static final String INTEGRATION = "mercadopago-cartao";

    /** Tempo limite de cada chamada. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Log do resultado. */
    private static final Logger LOG = LoggerFactory.getLogger(MercadoPagoEnvironmentCheck.class);

    /** Configuração. */
    private final RaykookieProperties properties;

    /** Estado do cartão. */
    private final CardPaymentsStatus status;

    /** Alertas. */
    private final OpsAlert alert;

    /** Se o servidor roda no perfil {@code prod}. */
    private final boolean production;

    /** Cliente HTTP. */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Leitor de JSON. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Cria a conferência.
     *
     * @param properties  configuração
     * @param status      estado do cartão
     * @param alert       alertas
     * @param environment ambiente do Spring (para saber se é produção)
     */
    public MercadoPagoEnvironmentCheck(RaykookieProperties properties, CardPaymentsStatus status, OpsAlert alert,
            Environment environment) {
        this.properties = properties;
        this.status = status;
        this.alert = alert;
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * Roda a conferência na subida, se o cartão foi pedido.
     *
     * @param args argumentos da aplicação (não usados)
     */
    @Override
    public void run(ApplicationArguments args) {
        if (properties.cardRequested()) {
            verify();
        }
    }

    /**
     * Confere as credenciais, liga ou desliga o cartão e alerta se algo falhou.
     *
     * @return o motivo da falha, ou vazio se o cartão foi ligado
     */
    public Optional<String> verify() {

        // ── 1. Confere o token e a chave pública.
        Optional<String> problem = problemWithAccessToken().or(this::problemWithPublicKey);

        // ── 2. Liga o cartão só sem problema nenhum.
        status.set(problem.isEmpty());
        if (problem.isEmpty()) {
            LOG.info("Cartão do Mercado Pago ligado: credenciais conferidas.");
            return problem;
        }

        // ── 3. Desliga, avisa e segue a subida.
        alert.integrationDown(INTEGRATION, new IllegalStateException(problem.get()));
        return problem;
    }

    /**
     * Problema com o access token.
     *
     * @return o motivo, ou vazio se o token foi aceito
     */
    private Optional<String> problemWithAccessToken() {

        // ── 1. O Mercado Pago aceita o token?
        HttpRequest request = get("/users/me").header("Authorization", "Bearer " + properties.mp().accessToken())
                .build();
        Response response = call(request);
        if (response.status() != 200) {
            return Optional.of("MP_ACCESS_TOKEN recusado pelo Mercado Pago (" + response.describe() + ").");
        }

        // ── 2. Em produção, um token de conta de teste cobraria nada de verdade.
        if (production && isTestAccount(response.body())) {
            return Optional.of("MP_ACCESS_TOKEN é de uma conta de teste, mas o servidor está em produção.");
        }
        return Optional.empty();
    }

    /**
     * Problema com a chave pública.
     *
     * @return o motivo, ou vazio se a chave foi aceita
     */
    private Optional<String> problemWithPublicKey() {
        String key = URLEncoder.encode(properties.mp().publicKey(), StandardCharsets.UTF_8);
        Response response = call(get("/v1/payment_methods/installments?bin=503143&amount=10&public_key=" + key)
                .build());
        if (response.status() != 200) {
            return Optional.of("MP_PUBLIC_KEY recusada pelo Mercado Pago (" + response.describe() + ").");
        }
        return Optional.empty();
    }

    /**
     * Diz se o perfil de {@code /users/me} é de uma conta de teste.
     *
     * @param body corpo de {@code /users/me}
     * @return verdadeiro se {@code tags} contém {@code test_user}
     */
    private boolean isTestAccount(String body) {
        JsonNode tags = json.readTree(body).path("tags");
        for (JsonNode tag : tags) {
            if ("test_user".equals(tag.asString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Começa um GET na API do Mercado Pago.
     *
     * @param path caminho com a consulta
     * @return o construtor da requisição
     */
    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(properties.mp().apiBase().replaceAll("/+$", "") + path))
                .timeout(TIMEOUT)
                .GET();
    }

    /**
     * Faz a chamada sem lançar.
     *
     * @param request a requisição
     * @return status e corpo; status 0 se a rede falhou
     */
    private Response call(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), null);
        } catch (IOException networkError) {
            return new Response(0, "", networkError.toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Response(0, "", "interrompido");
        }
    }

    /**
     * Resultado de uma chamada.
     *
     * @param status status HTTP, ou 0 se a rede falhou
     * @param body   corpo
     * @param error  erro de rede, se houve
     */
    private record Response(int status, String body, String error) {

        /**
         * Descrição curta para a mensagem de erro (sem o corpo, que pode ter dados da conta).
         *
         * @return {@code status N} ou o erro de rede
         */
        String describe() {
            return error == null ? "status " + status : "sem resposta: " + error;
        }
    }
}
