package br.com.kntap.raykookie.refunds.application.ports;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.refunds.domain.RefundRequestForm;
import java.util.List;
import java.util.Optional;

/**
 * As devoluções (tabelas {@code refunds} e {@code refund_photos}).
 */
public interface Refunds {

    /**
     * Grava uma devolução.
     *
     * @param refund dados
     * @return o id
     */
    long insert(NewRefund refund);

    /**
     * Grava as fotos de uma solicitação.
     *
     * @param refundId devolução
     * @param photos   fotos (na ordem)
     */
    void insertPhotos(long refundId, List<RefundRequestForm.Photo> photos);

    /**
     * Reserva a devolução para mandar ao Mercado Pago: trava a linha (outro envio simultâneo não a vê), marca
     * "processando" e conta a tentativa. Só dentro de uma transação.
     *
     * @param refundId devolução
     * @return o que mandar, ou vazio se não há o que mandar (ou outro envio está em andamento)
     */
    Optional<Claimed> claim(long refundId);

    /**
     * Grava a resposta do Mercado Pago.
     *
     * @param refundId   devolução
     * @param done       se foi concluída
     * @param mpRefundId id da devolução no Mercado Pago
     * @param mpStatus   status no Mercado Pago
     * @return verdadeiro se mudou (falso se já estava concluída)
     */
    boolean finishSent(long refundId, boolean done, String mpRefundId, String mpStatus);

    /**
     * Marca a falha e agenda a próxima tentativa.
     *
     * @param refundId    devolução
     * @param why         {@code saldo}, {@code prazo} ou {@code erro}
     * @param waitMinutes espera (nulo = sem nova tentativa automática)
     */
    void markFailed(long refundId, String why, Integer waitMinutes);

    /**
     * Devoluções cuja hora de tentar chegou, e as "processando" sem resposta há mais de 10 minutos.
     *
     * @param maxAttempts tentativas no máximo
     * @return os ids (até 20)
     */
    List<Long> due(int maxAttempts);

    /**
     * O cliente desiste da solicitação ainda não analisada.
     *
     * @param orderId  pedido
     * @param customer cliente
     * @return a solicitação desistida
     */
    Optional<Withdrawn> withdraw(long orderId, CustomerId customer);

    /**
     * Trava uma devolução da dona.
     *
     * @param refundId devolução
     * @param owner    dona
     * @return a devolução
     */
    Optional<RefundRow> lock(long refundId, OwnerId owner);

    /**
     * Aprova.
     *
     * @param refundId devolução
     * @param amount   valor aprovado
     * @param note     observação
     */
    void approve(long refundId, Money amount, String note);

    /**
     * Nega.
     *
     * @param refundId devolução
     * @param code     motivo
     * @param note     observação
     */
    void deny(long refundId, String code, String note);

    /**
     * Situação de uma devolução da dona.
     *
     * @param refundId devolução
     * @param owner    dona
     * @return a situação
     */
    Optional<String> status(long refundId, OwnerId owner);

    /**
     * Devolução nossa que corresponde a uma devolução do Mercado Pago.
     *
     * @param mpRefundId id no Mercado Pago
     * @return id e situação
     */
    Optional<Known> byMpRefundId(String mpRefundId);

    /**
     * Fecha uma devolução que o Mercado Pago concluiu.
     *
     * @param refundId devolução
     */
    void complete(long refundId);

    /**
     * Registra uma devolução feita direto no painel do Mercado Pago.
     *
     * @param orderId    pedido
     * @param owner      dona
     * @param customer   cliente
     * @param amount     valor
     * @param mpRefundId id no Mercado Pago
     */
    void insertExternal(long orderId, OwnerId owner, CustomerId customer, Money amount, String mpRefundId);

    /**
     * Pedidos com devolução enviada e ainda "processando" (conciliação).
     *
     * @return pedido e pagamento
     */
    List<Processing> processing();

    /**
     * Apaga as fotos de devoluções decididas há mais de 180 dias (o registro fica).
     *
     * @return quantas
     */
    int cleanupOldPhotos();

    /**
     * Devolução nova.
     *
     * @param orderId      pedido
     * @param owner        dona
     * @param customer     cliente
     * @param kind         {@code automatico}, {@code solicitado}, {@code loja} ou {@code sistema}
     * @param status       situação inicial
     * @param reasonCode   motivo
     * @param description  descrição
     * @param itemsJson    itens marcados (JSON)
     * @param requested    valor pedido
     * @param approved     valor aprovado (nulo = ainda não decidida)
     * @param decisionNote observação da decisão
     * @param priority     urgente
     */
    record NewRefund(long orderId, OwnerId owner, CustomerId customer, String kind, String status, String reasonCode,
            String description, String itemsJson, Money requested, Money approved, String decisionNote,
            boolean priority) {
    }

    /**
     * Devolução reservada para envio.
     *
     * @param id       devolução
     * @param orderId  pedido
     * @param amount   valor aprovado
     * @param attempts tentativas (com esta)
     */
    record Claimed(long id, long orderId, Money amount, int attempts) {
    }

    /**
     * Solicitação desistida.
     *
     * @param id          devolução
     * @param owner       dona
     * @param code        número do pedido
     * @param contactName cliente
     */
    record Withdrawn(long id, OwnerId owner, int code, String contactName) {
    }

    /**
     * Devolução travada.
     *
     * @param id        devolução
     * @param orderId   pedido
     * @param customer  cliente
     * @param status    situação
     * @param requested valor pedido
     */
    record RefundRow(long id, long orderId, CustomerId customer, String status, Money requested) {
    }

    /**
     * Devolução conhecida.
     *
     * @param id     devolução
     * @param status situação
     */
    record Known(long id, String status) {
    }

    /**
     * Pedido com devolução processando.
     *
     * @param orderId  pedido
     * @param paidMpId pagamento
     */
    record Processing(long orderId, String paidMpId) {
    }
}
