package br.com.kntap.raykookie.orders.api.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Map;

/**
 * Cobrança de um pedido recém-criado (implementada pelo módulo de pagamentos): gera o Pix ou cobra o cartão.
 */
public interface ChargeStarter {

    /**
     * Gera o Pix do pedido. Falha do Mercado Pago não derruba o pedido: ele fica pendente.
     *
     * @param order o pedido
     * @return {@code code}, {@code qr_base64} e {@code expires_in}, ou nulo se não deu para gerar
     */
    Map<String, Object> startPix(NewOrder order);

    /**
     * Cobra o cartão. Recusado, o pedido continua existindo, sem pagamento.
     *
     * @param order    o pedido
     * @param card     dados do cartão
     * @param clientIp IP real do cliente (limite de recusas)
     * @return {@code approved}, {@code pending} e {@code reason} como o Node responde
     */
    Map<String, Object> startCard(NewOrder order, Card card, String clientIp);

    /**
     * Pedido recém-criado.
     *
     * @param id       id
     * @param code     número que o cliente vê
     * @param owner    dona
     * @param customer cliente
     * @param email    e-mail do cliente
     * @param name     nome para contato
     * @param phone    telefone para contato (só dígitos)
     * @param total    total
     * @param items    quantos itens
     */
    record NewOrder(long id, int code, OwnerId owner, CustomerId customer, String email, String name, String phone,
            Money total, int items) {
    }

    /**
     * Cartão tokenizado no site/app (nunca o número).
     *
     * @param token        token do Mercado Pago
     * @param methodId     bandeira
     * @param issuerId     emissor (nulo em versões antigas do app)
     * @param cpf          CPF só com dígitos
     * @param installments parcelas
     * @param deviceId     device ID do antifraude do Mercado Pago (nulo se o app não mandou)
     * @param typeId       {@code credit_card}, {@code debit_card} ou {@code prepaid_card} (nulo = o gateway deduz da
     *                     bandeira)
     */
    record Card(String token, String methodId, String issuerId, String cpf, int installments, String deviceId,
            String typeId) {

        /**
         * Cartão sem o tipo informado (versões antigas do app e do site).
         *
         * @param token        token do Mercado Pago
         * @param methodId     bandeira
         * @param issuerId     emissor
         * @param cpf          CPF só com dígitos
         * @param installments parcelas
         * @param deviceId     device ID do antifraude
         */
        public Card(String token, String methodId, String issuerId, String cpf, int installments, String deviceId) {
            this(token, methodId, issuerId, cpf, installments, deviceId, null);
        }
    }
}
