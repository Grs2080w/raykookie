package br.com.kntap.raykookie.contract.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fotos das receitas: {@code PUT/DELETE /api/recipes/{id}/photo} e {@code GET /api/fotos/{id}} (Tarefa 4.4).
 */
class PhotosContractIT extends CatalogContractBase {

    /** Cabeçalho de PNG. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H'};

    /**
     * Um segmento de JPEG.
     *
     * @param marker  marcador (depois do {@code 0xFF})
     * @param payload conteúdo
     * @return os bytes do segmento
     */
    private static byte[] segment(int marker, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xff);
        out.write(marker);
        out.write((data.length + 2) >> 8);
        out.write((data.length + 2) & 0xff);
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * Um JPEG mínimo com JFIF, EXIF com GPS e um comentário.
     *
     * @return os bytes
     */
    private static byte[] jpegWithExif() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        out.writeBytes(segment(0xe0, "JFIF\0\1\1"));
        out.writeBytes(segment(0xe1, "Exif\0\0GPS-LATITUDE-23.5"));
        out.writeBytes(segment(0xfe, "tirada em casa"));
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xda, 0, 4, 0, 0, 1, 2, 3, 4, (byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }

    /**
     * Envia uma foto.
     *
     * @param recipe id da receita
     * @param bytes  conteúdo
     * @param type   tipo declarado (que o servidor ignora)
     * @return a resposta
     */
    private Response upload(long recipe, byte[] bytes, String type) {
        return asOwner().contentType(type).body(bytes).put("/api/recipes/" + recipe + "/photo");
    }

    /**
     * Envio e leitura, com o cache de cada caso.
     */
    @Test
    @DisplayName("PUT guarda; GET pela dona (private), pública com ?v= (immutable) e sem ?v= (5 min)")
    void uploadAndServe() {

        // ── 1. Envia um PNG declarado como JPEG: vale o conteúdo.
        long recipe = recipe(Map.of("name", unique("Bolo com foto")));
        assertJson(upload(recipe, PNG, "image/jpeg"), 200, "ok");
        assertThat(asOwner().get("/api/recipes/" + recipe).jsonPath().getString("photo_v")).matches("\\d+");

        // ── 2. A dona vê com cache privado.
        Response mine = asOwner().get("/api/fotos/" + recipe);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.contentType()).isEqualTo("image/png");
        assertThat(mine.getHeader("Cache-Control")).isEqualTo("private, max-age=60");
        assertThat(mine.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(mine.asByteArray()).isEqualTo(PNG);

        // ── 3. Público: com versão, guarda para sempre; sem versão, 5 minutos.
        Response versioned = api().queryParam("v", "1").get("/api/fotos/" + recipe);
        assertThat(versioned.getHeader("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(api().get("/api/fotos/" + recipe).getHeader("Cache-Control")).isEqualTo("public, max-age=300");

        // ── 4. Apagada, some.
        assertJson(asOwner().delete("/api/recipes/" + recipe + "/photo"), 200, "ok");
        assertThat(api().get("/api/fotos/" + recipe).statusCode()).isEqualTo(404);
    }

    /**
     * Envios recusados.
     */
    @Test
    @DisplayName("vazio 400, texto 415, acima de 2 MB 413, receita alheia 404")
    void rejectsBadUploads() {
        long recipe = recipe(Map.of("name", unique("Bolo sem foto")));
        assertError(upload(recipe, new byte[0], "image/jpeg"), 400, "Nenhuma imagem enviada.");
        assertError(upload(recipe, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
                415, "Use uma foto JPG, PNG ou WebP.");
        assertError(upload(recipe, new byte[2_000_001], "image/jpeg"), 413, "A foto é grande demais (máximo de 2 MB).");
        long foreign = seed.insert("INSERT INTO recipes (user_id, name) VALUES (?, 'Alheia') RETURNING id",
                otherAccount());
        assertError(upload(foreign, PNG, "image/png"), 404, "Receita não encontrada.");
    }

    /**
     * Foto de receita que não é da loja não é servida a ninguém de fora.
     */
    @Test
    @DisplayName("GET /api/fotos de receita alheia ou id inválido: 404")
    void foreignPhotoIsHidden() {
        long foreign = seed.insert("INSERT INTO recipes (user_id, name) VALUES (?, 'Alheia') RETURNING id",
                otherAccount());
        seed.exec("INSERT INTO recipe_photos (recipe_id, content_type, data) VALUES (?, 'image/png', ?)", foreign,
                PNG);
        assertThat(api().get("/api/fotos/" + foreign).statusCode()).isEqualTo(404);
        assertThat(asOwner().get("/api/fotos/" + foreign).statusCode()).isEqualTo(404);
        assertThat(api().get("/api/fotos/abc").statusCode()).isEqualTo(404);
    }

    /**
     * A foto 404 sai em JSON, como todo erro do Java (no Node era texto).
     */
    @Test
    @ChangedFromNode("G20")
    @DisplayName("foto inexistente: 404 em JSON")
    void missingPhotoIsJson() {
        assertError(api().get("/api/fotos/999999999"), 404, "Foto não encontrada.");
    }

    /**
     * O JPEG é guardado sem EXIF (localização), XMP e comentários.
     */
    @Test
    @ChangedFromNode("Tarefa 4.4: metadados do JPEG")
    @DisplayName("JPEG com EXIF de GPS volta sem o segmento APP1 e sem o comentário")
    void jpegMetadataIsStripped() {
        long recipe = recipe(Map.of("name", unique("Bolo com GPS")));
        assertJson(upload(recipe, jpegWithExif(), "image/jpeg"), 200, "ok");
        byte[] served = asOwner().get("/api/fotos/" + recipe).asByteArray();
        String text = new String(served, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS", "Exif", "casa").contains("JFIF");
        assertThat(served.length).isEqualTo(jpegWithExif().length - segment(0xe1, "Exif\0\0GPS-LATITUDE-23.5").length
                - segment(0xfe, "tirada em casa").length);
    }

    /**
     * Sem sessão: 401 no envio e na exclusão.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().contentType("image/png").body(PNG).put("/api/recipes/1/photo"), 401, SESSION_EXPIRED);
        assertError(api().delete("/api/recipes/1/photo"), 401, SESSION_EXPIRED);
    }
}
