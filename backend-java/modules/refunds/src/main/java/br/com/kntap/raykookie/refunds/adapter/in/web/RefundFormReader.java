package br.com.kntap.raykookie.refunds.adapter.in.web;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.Images;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.orders.api.rules.RefundRules;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Lê o multipart do pedido de devolução ({@code readRefundForm}): {@code reason_code}, {@code description},
 * {@code items} (JSON) e até 5 fotos {@code fotos}, de até 2 MB, JPG/PNG/WebP, JPEG sem metadados.
 */
final class RefundFormReader {

    /** JSON dos itens. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private RefundFormReader() {
    }

    /**
     * Lê e confere o formulário.
     *
     * @param request requisição
     * @return o pedido de devolução
     */
    static RefundRequestForm read(HttpServletRequest request) {

        // ── 1. Só multipart.
        if (!(request instanceof MultipartHttpServletRequest form)) {
            throw new InvalidInputException("Envie o pedido de devolução pelo aplicativo ou pelo site.");
        }

        // ── 2. Fotos: quantidade, tamanho e tipo; JPEG sem EXIF/localização.
        List<MultipartFile> files = form.getFiles("fotos").stream().filter(f -> f.getSize() > 0).toList();
        if (files.size() > RefundRules.MAX_REFUND_PHOTOS) {
            throw new InvalidInputException("Envie no máximo " + RefundRules.MAX_REFUND_PHOTOS + " fotos.");
        }
        List<RefundRequestForm.Photo> photos = new ArrayList<>();
        for (MultipartFile file : files) {
            photos.add(photo(file));
        }

        // ── 3. Itens em JSON.
        Object items;
        try {
            String raw = form.getParameter("items");
            items = JSON.readValue(raw == null ? "[]" : raw, Object.class);
        } catch (JacksonException e) {
            throw new InvalidInputException("Confira os itens marcados.");
        }
        return RefundRequestForm.of(form.getParameter("reason_code"), form.getParameter("description"), items, photos);
    }

    /**
     * Uma foto conferida.
     *
     * @param file arquivo
     * @return a foto
     */
    private static RefundRequestForm.Photo photo(MultipartFile file) {
        if (file.getSize() > RefundRules.MAX_REFUND_PHOTO_BYTES) {
            throw new DomainException("Uma das fotos é grande demais (máximo de 2 MB).", 413);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = Images.sniff(data).orElseThrow(() -> new DomainException("Use fotos JPG, PNG ou WebP.", 415));
        return new RefundRequestForm.Photo(type, "image/jpeg".equals(type) ? Images.stripJpegMetadata(data) : data);
    }
}
