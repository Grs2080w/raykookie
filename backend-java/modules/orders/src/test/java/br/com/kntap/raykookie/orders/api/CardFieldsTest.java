package br.com.kntap.raykookie.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dados do cartão das rotas de pagar de novo (mensagens das rotas do Node).
 */
class CardFieldsTest {

    /**
     * Cartão válido com um campo trocado.
     *
     * @param key   campo
     * @param value valor
     * @return o corpo
     */
    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> body = new HashMap<>(Map.of("card_token", "tok", "card_payment_method_id", "visa", "cpf",
                "529.982.247-25", "installments", "3"));
        body.put(key, value);
        return body;
    }

    /**
     * Regras.
     */
    @Test
    @DisplayName("token e bandeira, CPF válido, parcelas de 1 a 12, emissor opcional")
    void rules() {
        assertThatThrownBy(() -> CardFields.parse(with("card_token", " "))).hasMessage("Preencha os dados do cartão.");
        assertThatThrownBy(() -> CardFields.parse(with("card_payment_method_id", ""))).hasMessage(
                "Preencha os dados do cartão.");
        assertThatThrownBy(() -> CardFields.parse(with("cpf", "123"))).hasMessage("Informe um CPF válido.");
        assertThatThrownBy(() -> CardFields.parse(with("installments", 13))).hasMessage(
                "Escolha uma quantidade de parcelas válida.");
        assertThatThrownBy(() -> CardFields.parse(with("installments", 1.5))).hasMessage(
                "Escolha uma quantidade de parcelas válida.");
        assertThat(CardFields.parse(with("card_issuer_id", ""))).isEqualTo(new ChargeStarter.Card("tok", "visa", null,
                "52998224725", 3));
        assertThat(CardFields.parse(with("card_issuer_id", "24")).issuerId()).isEqualTo("24");
    }
}
