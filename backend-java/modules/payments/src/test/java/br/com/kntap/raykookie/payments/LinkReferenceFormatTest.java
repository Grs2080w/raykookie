package br.com.kntap.raykookie.payments;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.payments.application.LinkPayments;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O {@code external_reference} do link vai para a API Orders do Mercado Pago, que só aceita letras, números, hífen e
 * sublinhado (até 64 caracteres): com {@code link:} ela devolvia 400 e a página de pagamento não gerava o Pix.
 */
class LinkReferenceFormatTest {

    /** Padrão aceito pela API Orders. */
    private static final Pattern ORDERS_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * A referência montada para um link de qualquer tamanho passa no padrão da API Orders.
     */
    @Test
    @DisplayName("a referência externa do link segue o padrão aceito pela API Orders")
    void referenceMatchesOrdersPattern() {
        assertThat(LinkPayments.LINK_REF + 3).matches(ORDERS_PATTERN);
        assertThat(LinkPayments.LINK_REF + Long.MAX_VALUE).matches(ORDERS_PATTERN);
    }
}
