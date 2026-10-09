package br.com.kntap.raykookie.feedback.application;

import br.com.kntap.raykookie.feedback.application.ports.FeedbackStore;
import br.com.kntap.raykookie.feedback.domain.FeedbackRules;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Feedback dos clientes ({@code backend/lib/feedback.ts}): só quem tem conta envia; a dona só lê.
 */
@Service
public class FeedbackUseCases {

    /** Feedbacks. */
    private final FeedbackStore store;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /**
     * Cria os casos de uso.
     *
     * @param store     feedbacks
     * @param shopOwner a dona
     */
    public FeedbackUseCases(FeedbackStore store, ShopOwner shopOwner) {
        this.store = store;
        this.shopOwner = shopOwner;
    }

    /**
     * Grava o feedback ({@code submitFeedback}).
     *
     * @param customer cliente
     * @param rating   nota como veio do formulário
     * @param message  mensagem como veio
     * @param photos   fotos já conferidas
     * @return {@code ok} e o {@code id}
     */
    public Map<String, Object> submit(CustomerId customer, Object rating, Object message,
            List<FeedbackStore.Photo> photos) {

        // ── 1. A loja precisa ter dona.
        if (shopOwner.isPending()) {
            throw new DomainException("A loja está indisponível no momento.", 503);
        }

        // ── 2. Nota ({@code Math.trunc(Number(rating))}), mensagem e fotos.
        double number = NodeInput.jsNumber(NodeInput.text(rating));
        Object value = Double.isFinite(number) ? (Object) (long) number : Double.NaN;
        String text = NodeInput.cut(NodeInput.text(message).replaceAll("\\s+", " "), FeedbackRules.MAX_MESSAGE);
        Optional<String> error = FeedbackRules.validate(value, text);
        if (error.isPresent()) {
            throw new InvalidInputException(error.get());
        }
        if (photos.size() > FeedbackRules.MAX_PHOTOS) {
            throw new InvalidInputException("Envie no máximo " + FeedbackRules.MAX_PHOTOS + " fotos.");
        }

        // ── 3. Grava com as fotos.
        long id = store.insert(shopOwner.id(), customer, (Long) value, text, photos);
        return Map.of("ok", true, "id", id);
    }

    /**
     * Lista da dona.
     *
     * @param owner dona
     * @return {@code list}
     */
    public Map<String, Object> list(OwnerId owner) {
        return Map.of("list", store.list(owner, 100));
    }

    /**
     * Foto vista pela dona.
     *
     * @param id    feedback
     * @param n     índice
     * @param owner dona
     * @return a foto
     */
    public Optional<FeedbackStore.Photo> ownerPhoto(long id, int n, OwnerId owner) {
        return store.ownerPhoto(id, n, owner);
    }

    /**
     * Foto vista pelo próprio cliente.
     *
     * @param id       feedback
     * @param n        índice
     * @param customer cliente
     * @return a foto
     */
    public Optional<FeedbackStore.Photo> customerPhoto(long id, int n, CustomerId customer) {
        return store.customerPhoto(id, n, customer);
    }
}
