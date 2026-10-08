package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.application.PhotoUseCases;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Foto pública das receitas do cardápio: {@code /api/fotos/{id}}.
 */
@RestController
public class PhotosController {

    /** Casos de uso. */
    private final PhotoUseCases photos;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param photos casos de uso
     * @param actor  quem está chamando
     */
    public PhotosController(PhotoUseCases photos, CurrentActor actor) {
        this.photos = photos;
        this.actor = actor;
    }

    /**
     * A foto de uma receita da loja (ou da dona logada).
     *
     * @param id      receita
     * @param version {@code ?v=}, a versão da foto (só muda o cache)
     * @return a imagem
     * @throws NotFoundException se não houver foto visível
     */
    @Access(AccessLevel.OWNER_OR_PUBLIC)
    @GetMapping("/api/fotos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable String id,
            @RequestParam(value = "v", required = false) String version) {
        PhotoUseCases.ServedPhoto photo = photos.serve(actor.ownerIfAny(), id, version != null)
                .orElseThrow(() -> new NotFoundException("Foto não encontrada."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, photo.cache()).header("X-Content-Type-Options", "nosniff")
                .body(photo.data());
    }
}
