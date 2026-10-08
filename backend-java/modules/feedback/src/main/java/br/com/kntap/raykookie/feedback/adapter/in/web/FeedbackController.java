package br.com.kntap.raykookie.feedback.adapter.in.web;

import br.com.kntap.raykookie.feedback.application.FeedbackUseCases;
import br.com.kntap.raykookie.feedback.application.ports.FeedbackStore;
import br.com.kntap.raykookie.feedback.domain.FeedbackRules;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.Images;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.NodeInput;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * Feedback: o cliente envia (multipart, até 5 fotos) e vê as próprias fotos; a dona lê a lista e as fotos.
 */
@RestController
public class FeedbackController {

    /** Casos de uso. */
    private final FeedbackUseCases feedback;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param feedback casos de uso
     * @param actor    quem está chamando
     */
    public FeedbackController(FeedbackUseCases feedback, CurrentActor actor) {
        this.feedback = feedback;
        this.actor = actor;
    }

    /**
     * O cliente envia o feedback ({@code rating}, {@code message} e {@code fotos}).
     *
     * @param request requisição multipart
     * @return {@code ok} e {@code id}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(HttpServletRequest request) {

        // ── 1. Só multipart; fotos conferidas e sem metadados.
        if (!(request instanceof MultipartHttpServletRequest form)) {
            throw new InvalidInputException("Envie o feedback pelo aplicativo ou pelo site.");
        }
        List<MultipartFile> files = form.getFiles("fotos").stream().filter(f -> f.getSize() > 0).toList();
        if (files.size() > FeedbackRules.MAX_PHOTOS) {
            throw new InvalidInputException("Envie no máximo " + FeedbackRules.MAX_PHOTOS + " fotos.");
        }
        List<FeedbackStore.Photo> photos = new ArrayList<>();
        for (MultipartFile file : files) {
            photos.add(photo(file));
        }

        // ── 2. Grava.
        return feedback.submit(actor.requireCustomer(), form.getParameter("rating"), form.getParameter("message"),
                photos);
    }

    /**
     * Uma foto conferida.
     *
     * @param file arquivo
     * @return a foto
     */
    private static FeedbackStore.Photo photo(MultipartFile file) {
        if (file.getSize() > FeedbackRules.MAX_PHOTO_BYTES) {
            throw new DomainException("Uma das fotos é grande demais (máximo de 2 MB).", 413);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = Images.sniff(data).orElseThrow(() -> new DomainException("Use fotos JPG, PNG ou WebP.", 415));
        return new FeedbackStore.Photo(type, "image/jpeg".equals(type) ? Images.stripJpegMetadata(data) : data);
    }

    /**
     * Lista da dona.
     *
     * @return {@code list}
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/feedback")
    public Map<String, Object> list() {
        return feedback.list(actor.requireOwner());
    }

    /**
     * Foto vista pela dona.
     *
     * @param id feedback
     * @param n  índice
     * @return a imagem
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/feedback/{id}/fotos/{n}")
    public ResponseEntity<byte[]> ownerPhoto(@PathVariable String id, @PathVariable String n) {
        return photo(index(id).flatMap(i -> index(n).flatMap(k -> feedback.ownerPhoto(i, k.intValue(),
                actor.requireOwner()))));
    }

    /**
     * Foto vista pelo próprio cliente.
     *
     * @param id feedback
     * @param n  índice
     * @return a imagem
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/feedback/{id}/fotos/{n}")
    public ResponseEntity<byte[]> customerPhoto(@PathVariable String id, @PathVariable String n) {
        return photo(index(id).flatMap(i -> index(n).flatMap(k -> feedback.customerPhoto(i, k.intValue(),
                actor.requireCustomer()))));
    }

    /**
     * Número do caminho.
     *
     * @param text texto
     * @return o número
     */
    private static Optional<Long> index(String text) {
        return NodeInput.integer(text);
    }

    /**
     * Resposta da foto (404 sem corpo, como o Node).
     *
     * @param photo foto
     * @return a resposta
     */
    private static ResponseEntity<byte[]> photo(Optional<FeedbackStore.Photo> photo) {
        return photo.map(p -> ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, p.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").header("X-Content-Type-Options", "nosniff")
                .body(p.data())).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
