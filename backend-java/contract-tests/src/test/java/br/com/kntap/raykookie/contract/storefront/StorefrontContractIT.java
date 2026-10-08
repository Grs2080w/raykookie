package br.com.kntap.raykookie.contract.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vitrine pública: cardápio, stories, documentos legais e configuração pública (Tarefa 4.6).
 */
class StorefrontContractIT extends ContractTestBase {

    /** PNG de 1x1 para a foto. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /**
     * Abre a sessão da dona.
     */
    @BeforeEach
    void openSession() {
        owner = seed.owner(target.shopOwner());
    }

    /**
     * Uma receita da loja, com preço fixado, categoria, peso, selos, foto e um ingrediente (receita sem custo não
     * entra no cardápio, mesmo com preço fixado).
     *
     * @param name    nome
     * @param visible se aparece no cardápio
     * @return o id
     */
    private long recipe(String name, boolean visible) {
        long category = seed.insert("INSERT INTO categories (user_id, kind, name) VALUES (?, 'recipe', ?) RETURNING id",
                owner.id(), "Doces finos " + UUID.randomUUID().toString().substring(0, 6));
        long id = seed.insert("INSERT INTO recipes (user_id, category_id, name, sale_price, unit_weight_g, show_on_menu, "
                + "tags) VALUES (?, ?, ?, 12.5, 1250, ?, '{vegano}') RETURNING id", owner.id(), category, name, visible);
        long flour = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost) "
                + "VALUES (?, ?, 'g', 1000, 10) RETURNING id", owner.id(), "Farinha " + name);
        seed.exec("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (?, ?, 100)", id, flour);
        seed.exec("INSERT INTO recipe_photos (recipe_id, content_type, data) VALUES (?, 'image/png', ?)", id, PNG);
        return id;
    }

    /**
     * Cardápio: só receitas visíveis e com preço, com o que é público.
     */
    @Test
    @DisplayName("menu: item visível com preço, categoria, peso, foto e selos; escondido fica de fora")
    void menu() {

        // ── 1. Uma receita visível e uma escondida.
        String name = "Bolo da vitrine " + UUID.randomUUID().toString().substring(0, 6);
        long shown = recipe(name, true);
        long hidden = recipe(name + " escondido", false);

        // ── 2. O cardápio público.
        Response menu = api().get("/api/menu");
        assertJson(menu, 200, "menu");
        JsonPath json = menu.jsonPath();
        String item = "items.find { it.id == '" + shown + "' }";
        assertThat(json.getDouble(item + ".price")).isEqualTo(12.5);
        assertThat(json.getDouble(item + ".weight_g")).isEqualTo(1250.0);
        assertThat(json.getString(item + ".category")).startsWith("Doces finos");
        assertThat(json.getString(item + ".image")).matches("/api/fotos/" + shown + "\\?v=\\d+");
        assertThat(json.getList(item + ".tags", String.class)).containsExactly("vegano");
        assertThat(json.getList("items.id", String.class)).doesNotContain(String.valueOf(hidden));

        // ── 3. Nada de custo, margem ou ingredientes; "Outros" por último.
        assertThat(menu.asString().toLowerCase(Locale.ROOT)).doesNotContain("ingredients", "\"cost", "margin", "custo",
                "margem");
        List<String> groups = json.getList("groups.name", String.class);
        if (groups.contains("Outros")) {
            assertThat(groups.getLast()).isEqualTo("Outros");
        }
    }

    /**
     * Stories em PNG 1080×1920.
     *
     * @throws IOException se o PNG não abrir
     */
    @Test
    @DisplayName("stories: cardápio e produto em PNG 1080x1920; produto fora do cardápio 404")
    void stories() throws IOException {
        long shown = recipe("Story " + UUID.randomUUID().toString().substring(0, 6), true);
        long hidden = recipe("Story escondido " + UUID.randomUUID().toString().substring(0, 6), false);
        for (String path : List.of("/api/story/cardapio", "/api/story/produto/" + shown)) {
            Response story = api().get(path);
            assertThat(story.statusCode()).as(path).isEqualTo(200);
            assertThat(story.contentType()).startsWith("image/png");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(story.asByteArray()));
            assertThat(image.getWidth()).isEqualTo(1080);
            assertThat(image.getHeight()).isEqualTo(1920);
        }
        assertError(api().get("/api/story/produto/" + hidden), 404, "Produto não encontrado no cardápio.");
    }

    /**
     * Documentos legais iguais aos do Node.
     *
     * @throws IOException se o vetor não abrir
     */
    @Test
    @DisplayName("legal: termos e privacidade iguais ao texto do Node; documento desconhecido 404")
    void legal() throws IOException {
        JsonPath expected;
        try (InputStream in = getClass().getResourceAsStream("/rules-vectors/legal.json")) {
            expected = new JsonPath(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String doc : List.of("termos", "privacidade")) {
            Response response = api().get("/api/legal/" + doc);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
            assertThat(response.jsonPath().getMap("")).isEqualTo(expected.getMap(doc));
        }
        assertError(api().get("/api/legal/outro"), 404, "Documento não encontrado");
        assertThat(api().get("/api/legal/..%2Fetc%2Fpasswd").statusCode()).isEqualTo(404);
    }

    /**
     * Configuração pública.
     */
    @Test
    @DisplayName("payment-config e cliente/config: só o que é público")
    void publicConfig() {
        Response payment = api().get("/api/payment-config");
        assertJson(payment, 200, "payment-config");
        assertThat(payment.getHeader("Cache-Control")).isEqualTo("public, max-age=30");
        if (!payment.jsonPath().getBoolean("cardEnabled")) {
            assertThat(payment.jsonPath().getString("publicKey")).isNull();
        }
        assertJson(api().get("/api/cliente/config"), 200, "customer-config");
    }

    /**
     * Sem foto, o story do produto ainda sai (ilustração no lugar da foto).
     *
     * @throws IOException se o PNG não abrir
     */
    @Test
    @ChangedFromNode("Tarefa 4.6: story desenhado no Java")
    @DisplayName("story de produto sem foto: PNG")
    void storyWithoutPhoto() throws IOException {
        long id = recipe("Sem foto " + UUID.randomUUID().toString().substring(0, 6), true);
        seed.exec("DELETE FROM recipe_photos WHERE recipe_id = ?", id);
        Response story = api().get("/api/story/produto/" + id);
        assertThat(ImageIO.read(new ByteArrayInputStream(story.asByteArray())).getHeight()).isEqualTo(1920);
    }
}
