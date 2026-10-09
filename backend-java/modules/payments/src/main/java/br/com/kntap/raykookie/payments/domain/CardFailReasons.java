package br.com.kntap.raykookie.payments.domain;

import java.util.Map;

/**
 * Motivo da recusa do cartão em português ({@code CARD_FAIL_REASONS} de {@code backend/lib/mercadopago.ts}).
 */
public final class CardFailReasons {

    /** Mensagem quando o motivo não é um dos mapeados. */
    public static final String GENERIC = "Não conseguimos aprovar esse cartão. Tente outro cartão ou Pix.";

    /** {@code status_detail} → mensagem. */
    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("cc_rejected_bad_filled_security_code", "O código de segurança (CVV) está errado."),
            Map.entry("cc_rejected_bad_filled_date", "A validade do cartão está errada."),
            Map.entry("cc_rejected_bad_filled_card_number", "O número do cartão está errado."),
            Map.entry("cc_rejected_bad_filled_other", "Confira os dados do cartão e tente de novo."),
            Map.entry("cc_rejected_insufficient_amount", "Saldo ou limite insuficiente."),
            Map.entry("cc_rejected_call_for_authorize",
                    "O banco pediu para autorizar essa compra antes. Fale com o banco ou tente outro cartão."),
            Map.entry("cc_rejected_card_disabled",
                    "Esse cartão está desabilitado para compras online. Fale com o banco ou tente outro cartão."),
            Map.entry("cc_rejected_duplicated_payment", "Já existe um pagamento igual feito há pouco com esse cartão."),
            Map.entry("cc_rejected_high_risk", "O pagamento foi recusado por segurança. Tente outro cartão ou Pix."),
            Map.entry("cc_rejected_max_attempts", "Muitas tentativas com esse cartão. Tente outro cartão ou Pix."));

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private CardFailReasons() {
    }

    /**
     * Mensagem da recusa.
     *
     * @param statusDetail {@code status_detail} do Mercado Pago (pode ser nulo)
     * @return a mensagem
     */
    public static String of(String statusDetail) {
        return statusDetail == null ? GENERIC : REASONS.getOrDefault(statusDetail, GENERIC);
    }
}
