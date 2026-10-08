package br.com.kntap.raykookie.refunds.application.ports;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leituras das telas de devolução, no formato do Node.
 */
public interface RefundQueries {

    /**
     * Lista da dona por situação.
     *
     * @param owner    dona
     * @param statuses situações do grupo
     * @return as devoluções (até 100)
     */
    List<Map<String, Object>> list(OwnerId owner, List<String> statuses);

    /**
     * Quantas devoluções em cada situação.
     *
     * @param owner dona
     * @return situação → quantidade
     */
    Map<String, Integer> counts(OwnerId owner);

    /**
     * Valor aprovado ainda não concluído.
     *
     * @param owner dona
     * @return o valor (como o Node o escreve)
     */
    Object pendingAmount(OwnerId owner);

    /**
     * Uma devolução com o pedido.
     *
     * @param refundId devolução
     * @param owner    dona
     * @return a devolução
     */
    Optional<Map<String, Object>> detail(long refundId, OwnerId owner);

    /**
     * Índices das fotos.
     *
     * @param refundId devolução
     * @return os índices, em ordem
     */
    List<Integer> photoIndexes(long refundId);

    /**
     * Uma foto, se a devolução for da dona.
     *
     * @param refundId devolução
     * @param owner    dona
     * @param n        índice
     * @return a foto
     */
    Optional<Photo> ownerPhoto(long refundId, OwnerId owner, int n);

    /**
     * Uma foto, se a devolução for do cliente.
     *
     * @param refundId devolução
     * @param customer cliente
     * @param n        índice
     * @return a foto
     */
    Optional<Photo> customerPhoto(long refundId, CustomerId customer, int n);

    /**
     * Foto.
     *
     * @param contentType tipo
     * @param data        bytes
     */
    record Photo(String contentType, byte[] data) {
    }
}
