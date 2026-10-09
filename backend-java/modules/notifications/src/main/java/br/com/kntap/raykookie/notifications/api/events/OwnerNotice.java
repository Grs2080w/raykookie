package br.com.kntap.raykookie.notifications.api.events;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Aviso para os celulares da dona (app de gestão). Os módulos publicam um destes como evento na transação
 * que fez a mudança; o {@code notifications} manda o push só depois do commit (substitui o {@code notify…}
 * do Node, que avisava mesmo se a gravação desse errado).
 */
public sealed interface OwnerNotice {

    /**
     * A dona que recebe o aviso.
     *
     * @return o id
     */
    OwnerId owner();

    /**
     * Pedido novo da loja.
     *
     * @param owner    dona
     * @param orderId  id do pedido da loja
     * @param code     código público (nulo = usa o id)
     * @param customer nome de quem pediu
     * @param total    total
     * @param items    quantidade de itens
     */
    record NewOrder(OwnerId owner, long orderId, Integer code, String customer, Money total, int items)
            implements OwnerNotice {
    }

    /**
     * Pagamento confirmado.
     *
     * @param owner    dona
     * @param orderId  id do pedido da loja
     * @param code     código público
     * @param customer nome
     * @param total    total
     * @param items    quantidade de itens
     */
    record Paid(OwnerId owner, long orderId, Integer code, String customer, Money total, int items)
            implements OwnerNotice {
    }

    /**
     * Pedido cancelado pela cliente.
     *
     * @param owner    dona
     * @param orderId  id do pedido da loja
     * @param code     código público
     * @param customer nome
     * @param total    total
     * @param reason   motivo
     * @param refunded valor devolvido automaticamente (zero = nada)
     */
    record Cancelled(OwnerId owner, long orderId, Integer code, String customer, Money total, String reason,
            Money refunded) implements OwnerNotice {
    }

    /**
     * Solicitação de privacidade (LGPD) de um cliente.
     *
     * @param owner     dona
     * @param requestId id da solicitação
     * @param type      tipo
     */
    record PrivacyRequest(OwnerId owner, long requestId, String type) implements OwnerNotice {
    }

    /**
     * Novidade de uma devolução.
     *
     * @param owner     dona
     * @param kind      {@code solicitada}, {@code desistiu}, {@code concluida}, {@code saldo}, {@code falha} ou
     *                  {@code tardio}
     * @param refundId  id da devolução
     * @param code      código público do pedido
     * @param customer  nome do cliente (solicitada, desistiu)
     * @param amount    valor
     * @param priority  urgente (solicitada)
     * @param reason    motivo (solicitada)
     * @param photos    quantidade de fotos (solicitada)
     * @param stalled   parou e precisa de ação (saldo, falha)
     */
    record Refund(OwnerId owner, String kind, long refundId, int code, String customer, Money amount, boolean priority,
            String reason, int photos, boolean stalled) implements OwnerNotice {
    }

    /**
     * Alerta de preço ou de margem de uma receita.
     *
     * @param owner    dona
     * @param recipeId id da receita
     * @param name     nome da receita
     * @param kind     {@code margem} ou {@code preco}
     * @param before   antes (porcentagem ou reais)
     * @param after    depois
     * @param cause    explicação
     */
    record PriceAlert(OwnerId owner, long recipeId, String name, String kind, double before, double after,
            String cause) implements OwnerNotice {
    }
}
