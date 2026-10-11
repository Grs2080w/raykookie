package br.com.kntap.raykookie.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.storefront.api.StorefrontApi;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A vitrine pela rede, com o Postgres real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
            "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
                    + "classpath:db/testdata"})
class StorefrontWebIT extends PostgresTestBase {

    /** Porta. */
    @LocalServerPort
    int port;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** A vitrine vista pelos outros módulos. */
    @Autowired
    StorefrontApi storefront;

    /** Token da dona. */
    String token;

    /**
     * Entra como a dona.
     */
    @BeforeEach
    void login() {
        token = RestAssured.given().port(port).contentType(ContentType.JSON).header("x-client", "mobile")
                .body(Map.of("username", "dona", "password", "senha-de-teste-123")).post("/api/auth/login")
                .jsonPath().getString("token");
    }

    /**
     * Requisição como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification owner(Object body) {
        return RestAssured.given().port(port).header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(body);
    }

    /**
     * Uma receita à venda, com uma foto JPEG.
     *
     * @param name nome
     * @return o id
     * @throws IOException se a foto não for gerada
     */
    private long recipeOnMenu(String name) throws IOException {
        long id = jdbc.sql("""
                INSERT INTO recipes (user_id, name, sale_price, unit_weight_g, tags)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 15, 1200, '{vegano}') RETURNING id""")
                .param("name", name).query(Long.class).single();
        long flour = jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES ((SELECT id FROM users WHERE username = 'dona'), :name, 'g', 1000, 10) RETURNING id""")
                .param("name", "Farinha " + name).query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 100)")
                .param("r", id).param("i", flour).update();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        jdbc.sql("INSERT INTO recipe_photos (recipe_id, content_type, data) VALUES (:r, 'image/jpeg', :d)")
                .param("r", id).param("d", jpeg.toByteArray()).update();
        return id;
    }

    /**
     * Cardápio e stories.
     *
     * @throws IOException se o PNG não abrir
     */
    @Test
    @DisplayName("menu e stories: item com foto, PNG 1080x1920 do cardápio e do produto")
    void menuAndStories() throws IOException {

        // ── 1. O cardápio traz a receita com foto e peso.
        long id = recipeOnMenu("Bolo de vitrine IT");
        Response menu = RestAssured.given().port(port).get("/api/menu");
        assertThat(menu.jsonPath().getString("items.find { it.id == '" + id + "' }.image")).startsWith("/api/fotos/");
        assertThat(storefront.currentMenu().items()).isNotEmpty();

        // ── 2. Stories em PNG.
        for (String path : new String[] {"/api/story/cardapio", "/api/story/produto/" + id}) {
            Response story = RestAssured.given().port(port).get(path);
            assertThat(story.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(story.asByteArray()));
            assertThat(image.getWidth()).isEqualTo(1080);
            assertThat(image.getHeight()).isEqualTo(1920);
        }
        jdbc.sql("DELETE FROM recipe_photos WHERE recipe_id = :id").param("id", id).update();
        assertThat(RestAssured.given().port(port).get("/api/story/produto/" + id).statusCode()).isEqualTo(200);
        assertThat(RestAssured.given().port(port).get("/api/story/produto/999999").statusCode()).isEqualTo(404);
    }

    /**
     * Documentos legais.
     */
    @Test
    @DisplayName("legal: termos e privacidade; outro documento 404")
    void legal() {
        Response terms = RestAssured.given().port(port).get("/api/legal/termos");
        assertThat(terms.jsonPath().getString("id")).isEqualTo("termos");
        assertThat(terms.jsonPath().getList("sections")).hasSizeGreaterThan(8);
        assertThat(RestAssured.given().port(port).get("/api/legal/privacidade").jsonPath().getString("title"))
                .isEqualTo("Política de Privacidade");
        assertThat(RestAssured.given().port(port).get("/api/legal/x").statusCode()).isEqualTo(404);
    }

    /**
     * Endereços de retirada pela rede.
     */
    @Test
    @DisplayName("retirada: cria, ativa, edita, exclui e mostra o ativo em público")
    void pickup() {

        // ── 1. Dois endereços; o primeiro é o ativo e aparece em público.
        jdbc.sql("DELETE FROM pickup_addresses").update();
        Map<String, Object> body = Map.of("street", "Rua A", "number", "1", "district", "Centro", "city", "SP");
        long first = owner(body).post("/api/pickup-addresses").then().statusCode(201).extract().jsonPath().getLong("id");
        long second = owner(Map.of("street", "Rua B", "number", "2", "district", "Centro", "city", "SP",
                "active", true)).post("/api/pickup-addresses").jsonPath().getLong("id");
        assertThat(RestAssured.given().port(port).get("/api/pickup").jsonPath().getString("label")).isEqualTo("Rua B");

        // ── 2. Troca o ativo, edita, e a exclusão respeita o ativo.
        owner(Map.of("active", true)).patch("/api/pickup-addresses/" + first).then().statusCode(200);
        assertThat(owner(Map.of("label", "Ateliê", "street", "Rua A", "number", "1", "district", "Centro", "city",
                "SP")).patch("/api/pickup-addresses/" + first).jsonPath().getString("label")).isEqualTo("Ateliê");
        assertThat(owner(Map.of()).delete("/api/pickup-addresses/" + first).statusCode()).isEqualTo(409);
        owner(Map.of()).delete("/api/pickup-addresses/" + second).then().statusCode(200);
        owner(Map.of()).delete("/api/pickup-addresses/999999").then().statusCode(200);
        assertThat(owner(Map.of()).get("/api/pickup-addresses").jsonPath().getList("id", Long.class))
                .containsExactly(first);
        OwnerId dona = new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
        assertThat(storefront.activePickupAddress(dona)).isPresent();
        assertThat(owner(Map.of("active", true)).patch("/api/pickup-addresses/999999").statusCode()).isEqualTo(404);
    }

    /**
     * Página do Cardápio: lista de ingredientes e visibilidade refletem no cardápio público.
     *
     * @throws IOException se a foto não for gerada
     */
    @Test
    @DisplayName("página do Cardápio: preço e foto no editor; ingredientes ligados aparecem; produto desligado some")
    void menuPageFlags() throws IOException {

        // ── 1. O editor traz preço, foto e a chave dos ingredientes (desligada).
        long id = recipeOnMenu("Cookie da página IT");
        Response editor = owner(Map.of()).get("/api/recipes/cardapio");
        String item = "find { it.id == " + id + " }";
        assertThat(editor.jsonPath().getDouble(item + ".price")).isEqualTo(15.0);
        assertThat(editor.jsonPath().getString(item + ".photo_v")).isNotBlank();
        assertThat(editor.jsonPath().getBoolean(item + ".show_ingredients")).isFalse();
        String menuItem = "items.find { it.id == '" + id + "' }";
        assertThat(RestAssured.given().port(port).get("/api/menu").jsonPath().getList(menuItem + ".ingredients"))
                .isNull();

        // ── 2. Ligada, o cardápio mostra os ingredientes da receita padrão.
        owner(Map.of("show_ingredients", true)).patch("/api/recipes/" + id + "/cardapio").then().statusCode(200);
        assertThat(RestAssured.given().port(port).get("/api/menu").jsonPath().getList(menuItem + ".ingredients"))
                .containsExactly("Farinha Cookie da página IT");

        // ── 3. Desligado do cardápio: some de /api/menu e do story do produto.
        owner(Map.of("show_on_menu", false)).patch("/api/recipes/" + id + "/cardapio").then().statusCode(200);
        assertThat(RestAssured.given().port(port).get("/api/menu").jsonPath().getList("items.id"))
                .doesNotContain(String.valueOf(id));
        assertThat(RestAssured.given().port(port).get("/api/story/produto/" + id).statusCode()).isEqualTo(404);
    }
}
