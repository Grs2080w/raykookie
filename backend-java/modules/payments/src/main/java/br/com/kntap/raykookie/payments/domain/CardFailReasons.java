package br.com.kntap.raykookie.payments.domain;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Map.entry("cc_rejected_high_risk", "O Mercado Pago recusou por segurança e nada foi cobrado. Com o mesmo "
                    + "cartão, tentar de novo agora costuma ser recusado também: use outro cartão ou tente mais "
                    + "tarde."),
            Map.entry("cc_rejected_max_attempts", "Muitas tentativas com esse cartão. Tente outro cartão ou Pix."),
            Map.entry("cc_rejected_3ds_challenge", "A confirmação do cartão no seu banco não foi concluída. Tente de "
                    + "novo ou use outro cartão ou Pix."));

    /** Mensagem quando a API recusou os dados do cartão sem um motivo mapeado. */
    public static final String BAD_REQUEST = "Não conseguimos validar esse cartão. Confira os dados e tente de "
            + "novo.";

    /** Dados do cartão expiraram (o token vale poucos minutos e uma vez só). */
    public static final String TOKEN_EXPIRED = "Os dados do cartão expiraram. Toque em pagar de novo.";

    /** Status HTTP que não são erro nos dados do cartão: credencial, tempo, conflito e limite de chamadas. */
    private static final Set<Integer> NOT_REQUEST_ERRORS = Set.of(401, 403, 408, 409, 429);

    /** Código da causa no corpo do erro ({@code "cause":[{"code":2006,...}]}). */
    private static final Pattern CAUSE_CODE = Pattern.compile("\"code\"\\s*:\\s*\"?(\\d+)");

    /** Código da causa → mensagem. */
    private static final Map<String, String> REQUEST_ERRORS = Map.ofEntries(
            Map.entry("2006", TOKEN_EXPIRED),
            Map.entry("2062", TOKEN_EXPIRED),
            Map.entry("3003", TOKEN_EXPIRED),
            Map.entry("2067", "O CPF não confere com o cartão. Confira o CPF e tente de novo."),
            Map.entry("324", "O CPF não confere com o cartão. Confira o CPF e tente de novo."),
            Map.entry("3034", "O número do cartão não confere com a bandeira. Confira o número e tente de novo."),
            Map.entry("4033", "Esse parcelamento não está disponível para esse cartão. Escolha outro."),
            Map.entry("2132", "Esse parcelamento não está disponível para esse cartão. Escolha outro."));

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

    /**
     * Diz se o status HTTP é a API recusando os dados da cobrança (nada foi criado), e não falta de resposta.
     *
     * @param status status HTTP (0 = sem resposta)
     * @return verdadeiro para 4xx que não sejam credencial, tempo, conflito ou limite de chamadas
     */
    public static boolean isRequestError(int status) {
        return status >= 400 && status < 500 && !NOT_REQUEST_ERRORS.contains(status);
    }

    /**
     * Mensagem para a API recusando os dados da cobrança.
     *
     * @param body corpo do erro (pode ser nulo)
     * @return a mensagem
     */
    public static String ofRequestError(String body) {
        if (body == null) {
            return BAD_REQUEST;
        }
        Matcher code = CAUSE_CODE.matcher(body);
        while (code.find()) {
            String message = REQUEST_ERRORS.get(code.group(1));
            if (message != null) {
                return message;
            }
        }
        return BAD_REQUEST;
    }
}
