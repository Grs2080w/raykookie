package br.com.kntap.raykookie.orders.api;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.orders.api.ports.ChargeStarter;
import br.com.kntap.raykookie.orders.domain.Cpf;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.Map;
import java.util.Optional;

/**
 * Dados do cartão nas rotas de pagar de novo ("Tentar outro cartão" e o link de pagamento), com as mensagens dessas
 * rotas no Node.
 */
public final class CardFields {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private CardFields() {
    }

    /**
     * Confere os dados do cartão.
     *
     * @param body corpo JSON
     * @return o cartão (CPF só com dígitos)
     * @throws InvalidInputException com a mensagem do primeiro problema
     */
    public static ChargeStarter.Card parse(Map<String, Object> body) {

        // ── 1. Token, bandeira e CPF.
        String token = NodeInput.text(body.get("card_token"));
        String method = NodeInput.text(body.get("card_payment_method_id"));
        if (token.isEmpty() || method.isEmpty()) {
            throw new InvalidInputException("Preencha os dados do cartão.");
        }
        if (!Cpf.isValid(body.get("cpf"))) {
            throw new InvalidInputException("Informe um CPF válido.");
        }

        // ── 2. Parcelas e emissor.
        Optional<Long> installments = NodeInput.integer(body.get("installments"));
        if (installments.isEmpty() || installments.get() < 1 || installments.get() > ShopOrderForm.MAX_INSTALLMENTS) {
            throw new InvalidInputException("Escolha uma quantidade de parcelas válida.");
        }
        String issuer = NodeInput.text(body.get("card_issuer_id"));
        return new ChargeStarter.Card(token, method, issuer.isEmpty() ? null : issuer, Cpf.clean(body.get("cpf")),
                installments.get().intValue(), ShopOrderForm.Card.deviceId(body.get("device_id")),
                ShopOrderForm.Card.typeId(body.get("card_payment_type_id")));
    }
}
