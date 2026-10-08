package br.com.kntap.raykookie.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.catalog.api.IngredientForm;
import br.com.kntap.raykookie.catalog.api.MenuTags;
import br.com.kntap.raykookie.catalog.api.PricingEngine;
import br.com.kntap.raykookie.catalog.domain.RecipeForm;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.Images;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Imagens (vetores de {@code backend/tests/image.test.ts}), formulários e selos.
 */
class ImagesAndFormsTest {

    /**
     * Um segmento de JPEG.
     *
     * @param marker  marcador
     * @param payload conteúdo
     * @return os bytes
     */
    private static byte[] segment(int marker, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) marker, (byte) ((data.length + 2) >> 8),
            (byte) ((data.length + 2) & 0xff)});
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * Um JPEG com os segmentos dados.
     *
     * @param segments segmentos
     * @return os bytes
     */
    private static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        Arrays.stream(segments).forEach(out::writeBytes);
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xda, 0, 4, 0, 0, 1, 2, 3, 4, (byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }

    /**
     * Tipo pelo conteúdo.
     */
    @Test
    @DisplayName("reconhece JPEG, PNG e WebP pelo conteúdo; recusa GIF e texto")
    void sniffs() {
        assertThat(Images.sniff(jpeg(segment(0xe0, "JFIF\0")))).contains("image/jpeg");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I'};
        assertThat(Images.sniff(png)).contains("image/png");
        assertThat(Images.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
        assertThat(Images.sniff("GIF89a-------------".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(Images.sniff("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(Images.sniff(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})).isEmpty();
    }

    /**
     * EXIF, XMP e comentário saem; JFIF, tabelas e dados ficam (comparação de bytes).
     */
    @Test
    @DisplayName("tira EXIF com GPS, XMP e comentário, mantendo o resto byte a byte")
    void stripsJpegMetadata() {
        byte[] jfif = segment(0xe0, "JFIF\0\1\1");
        byte[] dqt = segment(0xdb, "\7\7\7\7\7\7\7\7\7\7");
        byte[] adobe = segment(0xee, "Adobe");
        byte[] stripped = Images.stripJpegMetadata(jpeg(jfif, segment(0xe1, "Exif\0\0GPS-LATITUDE-23.5"),
                segment(0xe1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>"), segment(0xfe, "tirada em casa"), dqt,
                new byte[] {(byte) 0xff}, adobe));
        assertThat(stripped).isEqualTo(jpeg(jfif, dqt, adobe));
    }

    /**
     * Arquivo estranho volta como veio.
     */
    @Test
    @DisplayName("estrutura inesperada volta como veio")
    void oddFilesAreKept() {
        byte[] odd = {(byte) 0xff, (byte) 0xd8, 0, 1, 2, 3, 4};
        assertThat(Images.stripJpegMetadata(odd)).isSameAs(odd);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        assertThat(Images.stripJpegMetadata(png)).isSameAs(png);
        byte[] badLength = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0x7f, 0x7f, 1};
        assertThat(Images.stripJpegMetadata(badLength)).isSameAs(badLength);
        byte[] noScan = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 2};
        assertThat(Images.stripJpegMetadata(noScan)).isSameAs(noScan);
    }

    /**
     * Ingrediente: números pt-BR, cortes e validações.
     */
    @Test
    @DisplayName("ingrediente: lê números pt-BR, corta textos e valida")
    void ingredientForm() {

        // ── 1. Caminho feliz.
        Map<String, Object> body = new HashMap<>(Map.of("name", "x".repeat(90), "unit", "ml", "package_size",
                "1.000", "package_cost", "12,5", "category_id", "7", "supplier", " Loja\u0000 "));
        IngredientForm form = IngredientForm.from(body);
        assertThat(form.name()).hasSize(80);
        assertThat(form.packageSize()).isEqualTo(1000);
        assertThat(form.packageCost()).isEqualTo(12.5);
        assertThat(form.categoryId()).isEqualTo(7L);
        assertThat(form.supplier()).isEqualTo("Loja");

        // ── 2. Erros.
        body.put("category_id", "abc");
        assertThatThrownBy(() -> IngredientForm.from(body)).isInstanceOf(InvalidInputException.class);
        assertThat(IngredientForm.isStockOnly(Map.of("stock_qty", 1))).isTrue();
        assertThat(IngredientForm.isStockOnly(Map.of("stock_qty", 1, "name", "x"))).isFalse();
    }

    /**
     * Receita: itens sem id válido saem; preço zero vira nenhum.
     */
    @Test
    @DisplayName("receita: itens sem id saem, preço 0 vira nulo, teto dos números grandes")
    void recipeForm() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Bolo");
        body.put("items", List.of(Map.of("ingredient_id", 3, "qty", "2,5"), Map.of("ingredient_id", "abc"), "lixo",
                Map.of("ingredient_id", 3, "qty", 1)));
        body.put("sale_price", 0);
        body.put("unit_weight_g", 1e12);
        RecipeForm form = RecipeForm.from(body);
        assertThat(form.items()).containsExactly(new RecipeForm.Item(3, 2.5), new RecipeForm.Item(3, 1));
        assertThat(form.ingredientIds()).containsExactly(3L);
        assertThat(form.salePrice()).isNull();
        assertThat(form.unitWeightG()).isEqualTo(99_999_999);
        assertThat(form.yieldUnits()).isEqualTo(1);
        assertThat(form.marginPct()).isEqualTo(100);
    }

    /**
     * Categorias, gastos e ajustes.
     */
    @Test
    @DisplayName("categoria, gasto e ajustes: padrões e limites")
    void simpleForms() {
        assertThat(SimpleForms.category(Map.of("name", "Doces", "color", "#ABCDEF")))
                .isEqualTo(new SimpleForms.Category("Doces", "tag", "#ABCDEF"));
        assertThat(SimpleForms.fixedCost(Map.of("name", "Luz", "kind", "variable", "amount", "1.500,50")))
                .isEqualTo(new SimpleForms.FixedCost(null, "Luz", "variable", 1500.5));
        assertThat(SimpleForms.settings(Map.of("min_margin_pct", 200)))
                .isEqualTo(new SimpleForms.Settings(0, 100, 95, 95.0));
        assertThat(SimpleForms.settings(Map.of("min_margin_pct", "")).minMargin()).isNull();
        assertThatThrownBy(() -> SimpleForms.categoryKind(Map.of("kind", "x")))
                .hasMessage("Tipo de categoria inválido.");
    }

    /**
     * Selos e textos de preço.
     */
    @Test
    @DisplayName("selos: só os válidos, sem repetição; toFixed(2) como o JavaScript")
    void tagsAndFixed() {
        assertThat(MenuTags.clean(List.of("vegano", "x", "vegano", "sem_gluten"))).containsExactly("vegano",
                "sem_gluten");
        assertThat(MenuTags.clean("vegano")).isEmpty();
        assertThat(MenuTags.label("contem_soja")).isEqualTo("Contém soja");
        assertThat(MenuTags.label("sumiu")).isEqualTo("sumiu");
        assertThat(PricingEngine.fixed2(1.005)).isEqualTo("1,00");
        assertThat(PricingEngine.fixed2(32)).isEqualTo("32,00");
        assertThat(PricingEngine.stockStatus(0, 1)).isEqualTo("zerado");
        assertThat(PricingEngine.stockStatus(1, 1)).isEqualTo("baixo");
        assertThat(PricingEngine.stockStatus(2, 1)).isEqualTo("ok");
    }
}
