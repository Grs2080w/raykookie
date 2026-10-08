package br.com.kntap.raykookie.contract.catalog;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base dos testes do catálogo (Tarefa 4.4): a dona com sessão aberta e atalhos para criar dados pela API.
 */
abstract class CatalogContractBase extends ContractTestBase {

    /** Mensagem de rota da dona sem sessão. */
    static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /** Sessão da dona da loja. */
    ContractSeed.Session owner;

    /**
     * Abre uma sessão da dona antes de cada teste.
     */
    @BeforeEach
    void openOwnerSession() {
        owner = seed.owner(target.shopOwner());
    }

    /**
     * Requisição como a dona.
     *
     * @return a requisição com o Bearer
     */
    RequestSpecification asOwner() {
        return api().header("Authorization", owner.bearer());
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    RequestSpecification asOwner(Object body) {
        return asOwner().contentType(ContentType.JSON).body(body);
    }

    /**
     * Um nome que nenhum outro teste usa (as categorias têm nome único por dona).
     *
     * @param prefix começo do nome
     * @return o nome
     */
    static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Cria um ingrediente pela API.
     *
     * @param name nome
     * @param unit unidade
     * @param size tamanho da embalagem
     * @param cost custo da embalagem
     * @return o id
     */
    long ingredient(String name, String unit, Object size, Object cost) {
        Response created = asOwner(Map.of("name", name, "unit", unit, "package_size", size, "package_cost", cost))
                .post("/api/ingredients");
        return created.then().statusCode(201).extract().jsonPath().getLong("id");
    }

    /**
     * Cria uma receita pela API.
     *
     * @param body corpo da receita
     * @return o id
     */
    long recipe(Map<String, Object> body) {
        return asOwner(body).post("/api/recipes").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    /**
     * Cria uma conta que não é a dona da loja, direto no banco.
     *
     * @return o id da conta
     */
    long otherAccount() {
        return seed.insert("INSERT INTO users (username, password_hash) VALUES (?, 'x') RETURNING id",
                unique("outra").replace(' ', '-'));
    }
}
