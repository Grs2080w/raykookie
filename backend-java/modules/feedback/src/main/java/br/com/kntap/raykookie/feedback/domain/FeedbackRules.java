package br.com.kntap.raykookie.feedback.domain;

import java.util.Optional;

/**
 * Feedback do cliente ({@code backend/shared/feedback.ts}): nota de 1 a 5 (obrigatória), mensagem de até 1000
 * caracteres e até 5 fotos de até 2 MB.
 */
public final class FeedbackRules {

    /** Fotos no máximo. */
    public static final int MAX_PHOTOS = 5;

    /** Tamanho máximo de cada foto. */
    public static final int MAX_PHOTO_BYTES = 2_000_000;

    /** Tamanho máximo da mensagem. */
    public static final int MAX_MESSAGE = 1000;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private FeedbackRules() {
    }

    /**
     * Confere nota e mensagem ({@code validateFeedback}): a nota precisa ser um número inteiro de 1 a 5 e a
     * mensagem, um texto.
     *
     * @param rating  nota (como veio)
     * @param message mensagem (como veio)
     * @return a mensagem de erro, ou vazio se estiver certo
     */
    public static Optional<String> validate(Object rating, Object message) {
        boolean integer = rating instanceof Integer || rating instanceof Long
                || rating instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                        && !Double.isInfinite(n.doubleValue());
        long value = integer ? ((Number) rating).longValue() : 0;
        if (!integer || value < 1 || value > 5) {
            return Optional.of("Escolha uma nota de 1 a 5 estrelas.");
        }
        if (!(message instanceof String text)) {
            return Optional.of("Mensagem inválida.");
        }
        if (text.length() > MAX_MESSAGE) {
            return Optional.of("A mensagem pode ter no máximo " + MAX_MESSAGE + " caracteres.");
        }
        return Optional.empty();
    }
}
