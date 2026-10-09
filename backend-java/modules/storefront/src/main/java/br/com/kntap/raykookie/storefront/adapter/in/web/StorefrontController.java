package br.com.kntap.raykookie.storefront.adapter.in.web;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.storefront.application.LegalDocuments;
import br.com.kntap.raykookie.storefront.application.StorefrontService;
import br.com.kntap.raykookie.storefront.application.StoryUseCases;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Vitrine pública: {@code /api/menu}, {@code /api/story/*} e {@code /api/legal/{doc}}.
 */
@RestController
public class StorefrontController {

    /** Cardápio. */
    private final StorefrontService storefront;

    /** Stories. */
    private final StoryUseCases stories;

    /** Textos legais. */
    private final LegalDocuments legal;

    /**
     * Cria o controller.
     *
     * @param storefront cardápio
     * @param stories    stories
     * @param legal      textos legais
     */
    public StorefrontController(StorefrontService storefront, StoryUseCases stories, LegalDocuments legal) {
        this.storefront = storefront;
        this.stories = stories;
        this.legal = legal;
    }

    /**
     * Cardápio público (só nome, preço, categoria, peso, foto e selos).
     *
     * @return itens e grupos
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/menu")
    public Map<String, Object> menu() {
        return storefront.currentMenu().json();
    }

    /**
     * Story do cardápio (PNG 1080×1920).
     *
     * @return a imagem
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/story/cardapio")
    public ResponseEntity<byte[]> menuStory() {
        return png(stories.menu(), "story-cardapio.png");
    }

    /**
     * Story de um produto do cardápio.
     *
     * @param id id da receita
     * @return a imagem
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/story/produto/{id}")
    public ResponseEntity<byte[]> productStory(@PathVariable String id) {
        return png(stories.product(id), "story-" + id + ".png");
    }

    /**
     * PNG com o cache de 5 minutos e o nome do arquivo.
     *
     * @param data     imagem
     * @param filename nome
     * @return a resposta
     */
    private static ResponseEntity<byte[]> png(byte[] data, String filename) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300").body(data);
    }

    /**
     * Termos de Serviço ou Política de Privacidade (para os apps; precisa abrir antes de criar a conta).
     *
     * @param doc {@code termos} ou {@code privacidade}
     * @return o documento
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/legal/{doc}")
    public ResponseEntity<JsonNode> legal(@PathVariable String doc) {
        JsonNode document = legal.find(doc).orElseThrow(() -> new NotFoundException("Documento não encontrado"));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(document);
    }
}
