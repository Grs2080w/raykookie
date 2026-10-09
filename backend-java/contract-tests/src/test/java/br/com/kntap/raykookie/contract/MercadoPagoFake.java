package br.com.kntap.raykookie.contract;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * O Mercado Pago falso que o servidor sob teste chama ({@code MP_API_BASE=http://127.0.0.1:<porta>}).
 * Serve as gravações reais do sandbox de {@code src/test/resources/mp-recordings/} (Tarefa 3.1); enquanto
 * elas não existem, a suíte cobre só as rotas que não falam com o Mercado Pago.
 *
 * <p>Também assina notificações de webhook com o mesmo HMAC do Node ({@code backend/lib/mercadopago.ts}).
 */
public final class MercadoPagoFake implements AutoCloseable {

    /** Pasta das gravações no classpath (mapeamentos do WireMock). */
    static final String RECORDINGS = "mp-recordings";

    /** O servidor falso. */
    private final WireMockServer server;

    /**
     * Sobe o falso na porta combinada com o servidor sob teste.
     *
     * @param port porta ({@code CONTRACT_MP_FAKE_PORT}); 0 = qualquer uma livre
     */
    public MercadoPagoFake(int port) {
        this.server = new WireMockServer(wireMockConfig().port(port).usingFilesUnderClasspath(RECORDINGS));
        this.server.start();
    }

    /**
     * Endereço do falso.
     *
     * @return {@code http://localhost:<porta>}
     */
    public String baseUrl() {
        return server.baseUrl();
    }

    /**
     * O WireMock por baixo (para os testes conferirem o que o servidor pediu).
     *
     * @return o servidor
     */
    public WireMockServer server() {
        return server;
    }

    /**
     * Assina uma notificação como o Mercado Pago: {@code ts=<ts>,v1=<hmac>} sobre
     * {@code id:<dataId>;request-id:<requestId>;ts:<ts>;}.
     *
     * @param secret    {@code MP_WEBHOOK_SECRET} do servidor sob teste
     * @param dataId    {@code data.id} da notificação
     * @param requestId cabeçalho {@code x-request-id}
     * @param ts        instante da assinatura (segundos Unix)
     * @return o valor do cabeçalho {@code x-signature}
     */
    public static String signWebhook(String secret, String dataId, String requestId, long ts) {
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        return "ts=" + ts + ",v1=" + hmacSha256Hex(secret, manifest);
    }

    /**
     * HMAC-SHA256 em hex minúsculo.
     *
     * @param secret  chave
     * @param message mensagem
     * @return o HMAC
     */
    static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Para o falso.
     */
    @Override
    public void close() {
        server.stop();
    }
}
