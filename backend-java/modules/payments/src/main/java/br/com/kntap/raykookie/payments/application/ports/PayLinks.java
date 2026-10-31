package br.com.kntap.raykookie.payments.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Links de pagamento dos pedidos registrados à mão (tabela {@code order_pay_links}) e a promoção do pedido pago a
 * pedido do app ({@code backend/lib/pay-link.ts}).
 */
public interface PayLinks {

    /**
     * A linha da tela Pedidos que vai ganhar o link.
     *
     * @param orderId linha
     * @param owner   dona
     * @return a linha
     */
    Optional<LinkOrder> order(long orderId, OwnerId owner);

    /**
     * O link que já existe para a linha.
     *
     * @param orderId linha
     * @return valor, Pix atual e se foi pago
     */
    Optional<Previous> previous(long orderId);

    /**
     * Cria o link ou troca o valor (o Pix do valor antigo some junto); nunca mexe em link pago.
     *
     * @param orderId linha
     * @param token   token novo (usado só se o link ainda não existir)
     * @param amount  valor
     * @return o token, ou vazio se o link já foi pago
     */
    Optional<String> upsert(long orderId, String token, Money amount);

    /**
     * O link pelo token, com a linha.
     *
     * @param token token
     * @return o link
     */
    Optional<Link> byToken(String token);

    /**
     * Quem é o convidado de um link pago.
     *
     * @param token token
     * @return o link, o pedido do app e o cliente (se já promovido)
     */
    Optional<Guest> guest(String token);

    /**
     * Grava o Pix gerado.
     *
     * @param linkId    link
     * @param paymentId pagamento
     * @param code      copia e cola
     * @param qrBase64  QR
     * @param expiresAt validade
     * @param attempt   número do Pix
     */
    void savePix(long linkId, String paymentId, String code, String qrBase64, Instant expiresAt, int attempt);

    /**
     * Diz se o link ou a linha já estão pagos (releitura depois de uma corrida com o webhook).
     *
     * @param linkId link
     * @return verdadeiro se pago
     */
    boolean paid(long linkId);

    /**
     * Junta o pagamento aprovado do cartão ao link (é a guarda que {@code markPaid} confere).
     *
     * @param linkId       link
     * @param paymentId    pagamento
     * @param installments parcelas (nulo = não informado)
     */
    void addCardPayment(long linkId, String paymentId, Integer installments);

    /**
     * Conta uma tentativa de cartão sem aprovação.
     *
     * @param linkId    link
     * @param paymentId pagamento pendente a guardar (nulo = recusa)
     */
    void countCardAttempt(long linkId, String paymentId);

    /**
     * Trava a linha do link (mesma ordem de trava em todo lugar: linha, depois link).
     *
     * @param linkId link
     * @return situação e pagamento da linha antes
     */
    Optional<LineState> lockLine(long linkId);

    /**
     * Marca o link pago, se o pagamento é dele e cobre o valor.
     *
     * @param linkId     link
     * @param paymentId  pagamento
     * @param paidAmount valor pago
     * @return a linha marcada, ou vazio se não se aplica
     */
    Optional<Long> markPaid(long linkId, String paymentId, Money paidAmount);

    /**
     * Trava o link para promover.
     *
     * @param linkId link
     * @return os dados da promoção (vazio se já promovido)
     */
    Optional<PromotionSource> lockForPromotion(long linkId);

    /**
     * Link antigo pago e ainda não promovido.
     *
     * @param linkId link
     * @return o pagamento que o pagou
     */
    Optional<String> lockLegacyPaid(long linkId);

    /**
     * Grava o pedido do app de um link pago: cliente convidado, pedido ligado à linha e ao link.
     *
     * @param promotion dados
     * @return o pedido do app
     */
    long promote(Promotion promotion);

    /**
     * Link antigo entregue: o prazo de devolução conta do pagamento antigo.
     *
     * @param shopOrderId pedido do app
     */
    void deliveredAtLegacyPayment(long shopOrderId);

    /**
     * Links com Pix gerado e não pagos dos últimos 2 dias.
     *
     * @return id e pagamentos de cada um
     */
    List<OpenLink> open();

    /**
     * Links pagos ainda não promovidos.
     *
     * @return os ids
     */
    List<Long> legacy();

    /**
     * Pedidos promovidos e cancelados pela loja sem nenhuma devolução registrada (falha depois do commit).
     *
     * @return os pedidos
     */
    List<Long> orphans();

    /**
     * Link de um pagamento já promovido (estorno a conciliar).
     *
     * @param linkId link
     * @return pedido do app e pagamento que pagou
     */
    Optional<Promoted> promoted(long linkId);

    /**
     * Linha para o link.
     *
     * @param status       situação
     * @param payment      pagamento
     * @param shopOrderId  pedido do app (nulo = registrada à mão)
     * @param total        quantidade × preço
     * @param grouped      linha de pedido com vários itens (o link cobra uma linha só)
     */
    record LinkOrder(String status, String payment, Long shopOrderId, BigDecimal total, boolean grouped) {
    }

    /**
     * Link existente.
     *
     * @param amount      valor
     * @param mpPaymentId Pix atual
     * @param paid        se foi pago
     */
    record Previous(Money amount, String mpPaymentId, boolean paid) {
    }

    /**
     * Link com a linha.
     *
     * @param id           link
     * @param amount       valor
     * @param paidAt       quando foi pago
     * @param mpPaymentId  Pix atual
     * @param pixQrCode    copia e cola
     * @param pixQrBase64  QR
     * @param pixExpiresAt validade
     * @param pixAttempts  Pix gerados
     * @param cardAttempts tentativas de cartão
     * @param number       número da linha
     * @param customer     cliente
     * @param product      produto
     * @param qty          quantidade
     * @param unitPrice    preço
     * @param status       situação
     * @param payment      pagamento
     * @param deliveryDate entrega
     */
    record Link(long id, Money amount, Instant paidAt, String mpPaymentId, String pixQrCode, String pixQrBase64,
            Instant pixExpiresAt, int pixAttempts, int cardAttempts, int number, String customer, String product,
            BigDecimal qty, BigDecimal unitPrice, String status, String payment, String deliveryDate) {

        /**
         * Total atual da linha, em centavos arredondados.
         *
         * @return quantidade × preço
         */
        public Money lineTotal() {
            return Money.fromDecimal(qty.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    /**
     * Convidado de um link.
     *
     * @param linkId      link
     * @param paid        se foi pago
     * @param shopOrderId pedido do app (nulo = ainda não promovido)
     * @param customerId  cliente convidado
     */
    record Guest(long linkId, boolean paid, Long shopOrderId, Long customerId) {
    }

    /**
     * Linha antes de marcar pago.
     *
     * @param orderId linha
     * @param status  situação
     * @param payment pagamento
     */
    record LineState(long orderId, String status, String payment) {
    }

    /**
     * O que a promoção precisa.
     *
     * @param amount       valor do link
     * @param paymentIds   pagamentos gerados
     * @param orderId      linha
     * @param owner        dona
     * @param customer     cliente
     * @param product      produto
     * @param qty          quantidade
     * @param status       situação da linha
     * @param payment      pagamento da linha
     * @param orderDate    data do pedido
     * @param deliveryDate entrega
     */
    record PromotionSource(Money amount, List<String> paymentIds, long orderId, OwnerId owner, String customer,
            String product, BigDecimal qty, String status, String payment, LocalDate orderDate,
            LocalDate deliveryDate) {
    }

    /**
     * Pedido do app a gravar.
     *
     * @param linkId    link
     * @param source    dados do link
     * @param code      número que o cliente vê
     * @param paidMpId  pagamento aprovado
     * @param itemName  nome do item ("2 × Bolo")
     * @param cancelled se a linha já estava cancelada
     */
    record Promotion(long linkId, PromotionSource source, int code, String paidMpId, String itemName,
            boolean cancelled) {
    }

    /**
     * Link com cobrança em aberto.
     *
     * @param id         link
     * @param paymentIds pagamentos
     */
    record OpenLink(long id, List<String> paymentIds) {
    }

    /**
     * Link promovido.
     *
     * @param shopOrderId pedido do app
     * @param paidMpId    pagamento que pagou
     */
    record Promoted(Long shopOrderId, String paidMpId) {
    }
}
