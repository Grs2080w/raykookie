package br.com.kntap.raykookie.feedback.application.ports;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Feedback e fotos (tabelas {@code feedback} e {@code feedback_photos}).
 */
public interface FeedbackStore {

    /**
     * Grava o feedback com as fotos.
     *
     * @param owner    dona
     * @param customer cliente
     * @param rating   nota
     * @param message  mensagem
     * @param photos   fotos
     * @return o id
     */
    long insert(OwnerId owner, CustomerId customer, long rating, String message, List<Photo> photos);

    /**
     * Lista da dona, mais recentes primeiro.
     *
     * @param owner dona
     * @param limit quantos
     * @return os feedbacks no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner, int limit);

    /**
     * Foto de um feedback da dona.
     *
     * @param feedbackId feedback
     * @param n          índice
     * @param owner      dona
     * @return a foto
     */
    Optional<Photo> ownerPhoto(long feedbackId, int n, OwnerId owner);

    /**
     * Foto de um feedback do próprio cliente.
     *
     * @param feedbackId feedback
     * @param n          índice
     * @param customer   cliente
     * @return a foto
     */
    Optional<Photo> customerPhoto(long feedbackId, int n, CustomerId customer);

    /**
     * Foto.
     *
     * @param contentType tipo
     * @param data        bytes
     */
    record Photo(String contentType, byte[] data) {
    }
}
