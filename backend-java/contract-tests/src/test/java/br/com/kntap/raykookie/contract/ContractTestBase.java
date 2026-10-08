package br.com.kntap.raykookie.contract;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base dos testes de contrato: servidor sob teste, semeador do banco e Mercado Pago falso. Todo teste
 * confere status, {@code content-type}, corpo (JSON Schema em {@code schemas/<nome>.json}) e mensagem de
 * erro em português.
 */
public abstract class ContractTestBase {

    /** O servidor e o banco sob teste. */
    protected static ContractTarget target;

    /** Semeador do banco descartável. */
    protected static ContractSeed seed;

    /** Mercado Pago falso (só sobe se {@code CONTRACT_MP_FAKE_PORT} foi definida). */
    protected static MercadoPagoFake mercadoPago;

    /**
     * Lê o alvo e sobe o falso do Mercado Pago na porta combinada.
     */
    @BeforeAll
    static void connect() {
        target = ContractTarget.fromEnvironment();
        seed = new ContractSeed(target);
        if (target.mpFakePort() > 0 && mercadoPago == null) {
            mercadoPago = new MercadoPagoFake(target.mpFakePort());
        }
    }

    /**
     * Para o falso do Mercado Pago no fim da classe.
     */
    @AfterAll
    static void disconnect() {
        if (mercadoPago != null) {
            mercadoPago.close();
            mercadoPago = null;
        }
    }

    /**
     * Requisição para o servidor sob teste.
     *
     * @return a requisição
     */
    protected static RequestSpecification api() {
        return RestAssured.given().baseUri(target.baseUrl().toString()).redirects().follow(false);
    }

    /**
     * Confere uma resposta JSON de sucesso contra o schema dela.
     *
     * @param response resposta
     * @param status   status esperado
     * @param schema   nome do schema em {@code schemas/} (sem {@code .json})
     */
    protected static void assertJson(Response response, int status, String schema) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        response.then().body(matchesJsonSchemaInClasspath("schemas/" + schema + ".json"));
    }

    /**
     * Confere uma resposta de erro: status, JSON e a mensagem exata em português.
     *
     * @param response resposta
     * @param status   status esperado
     * @param message  mensagem esperada em {@code error}
     */
    protected static void assertError(Response response, int status, String message) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.jsonPath().getString("error")).isEqualTo(message);
    }
}
