package br.com.kntap.raykookie.payments.api;

import java.util.List;

/**
 * Bandeiras de cartão que o Mercado Pago aceita para a conta da loja (crédito, débito e pré-pago), para o site e os
 * apps mostrarem ao cliente antes de ele digitar o cartão.
 */
public interface CardBrands {

    /**
     * Meios de cartão ativos na conta. Vazio se o Mercado Pago não está configurado ou não respondeu (quem chama
     * simplesmente não mostra a faixa de bandeiras).
     *
     * @return os meios de cartão
     */
    List<CardBrand> list();

    /**
     * Um meio de cartão.
     *
     * @param id   id do meio no Mercado Pago ({@code visa}, {@code master}, {@code debelo}…)
     * @param name nome para mostrar ({@code Visa}, {@code Elo Débito}…)
     * @param type {@code credit_card}, {@code debit_card} ou {@code prepaid_card}
     * @param logo endereço da imagem da bandeira
     */
    record CardBrand(String id, String name, String type, String logo) {
    }
}
