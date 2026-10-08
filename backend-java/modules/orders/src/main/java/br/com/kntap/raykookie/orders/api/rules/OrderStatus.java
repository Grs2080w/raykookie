package br.com.kntap.raykookie.orders.api.rules;

import java.util.List;
import java.util.Objects;

/**
 * Situação dos itens de um pedido.
 */
public final class OrderStatus {

    /** Situações de um item, da menos à mais adiantada (cancelado à parte). */
    public static final List<String> RANK = List.of("novo", "producao", "pronto", "entregue");

    /** Situações aceitas na tela Pedidos. */
    public static final List<String> ALL = List.of("novo", "producao", "pronto", "entregue", "cancelado");

    /** Pagamentos aceitos na tela Pedidos. */
    public static final List<String> PAYMENTS = List.of("pendente", "sinal", "pago");

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private OrderStatus() {
    }

    /**
     * Situação geral: a do item menos adiantado, ignorando cancelados ({@code overallStatus}).
     *
     * @param statuses situação de cada item (pode ter nulos)
     * @return a situação geral ({@code cancelado} se não houver item vivo)
     */
    public static String overall(List<String> statuses) {
        List<String> live = statuses == null ? List.of() : statuses.stream().filter(Objects::nonNull)
                .filter(s -> !s.isEmpty() && !"cancelado".equals(s)).toList();
        if (live.isEmpty()) {
            return "cancelado";
        }
        String least = live.getFirst();
        for (String status : live.subList(1, live.size())) {
            least = RANK.indexOf(least) <= RANK.indexOf(status) ? least : status;
        }
        return least;
    }
}
