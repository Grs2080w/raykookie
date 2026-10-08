package br.com.kntap.raykookie.orders.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Motivos de cancelamento, devolução e negativa, com os rótulos que o cliente e a dona leem (porte de
 * {@code backend/shared/refund.ts}).
 */
public final class Reasons {

    /** Cancelamento pelo cliente, antes de a produção começar. */
    public static final List<Reason> CANCEL = List.of(
            new Reason("errei_pedido", "Fiz o pedido errado (sabor, item ou quantidade)"),
            new Reason("data_horario", "A data ou o horário não servem mais"),
            new Reason("retirada_entrega", "Não vou conseguir retirar ou receber"),
            new Reason("pagamento", "Tive problema com o pagamento ou o Pix"),
            new Reason("demora_confirmacao", "A confirmação está demorando"),
            new Reason("preco", "Achei caro ou encontrei outra opção"),
            new Reason("nao_preciso", "Não preciso mais (o evento foi cancelado ou desisti)"),
            new Reason("outro", "Outro motivo"));

    /** Pedido de devolução depois de a produção ter começado. */
    public static final List<RefundReason> REFUND = List.of(
            new RefundReason("nao_recebi", "Não recebi ou não ficou pronto no horário combinado", "nao", false, null),
            new RefundReason("item_errado", "Veio item, sabor ou recheio errado", "sim", false, null),
            new RefundReason("faltou_item", "Faltou item ou veio em menor quantidade", "sim", false, null),
            new RefundReason("avariado", "Chegou amassado, quebrado ou derretido", "sim", false, null),
            new RefundReason("qualidade", "Problema de qualidade (cru, queimado, seco, sabor estranho)", "sim", false,
                    null),
            new RefundReason("estragado", "Estragado, com mofo ou com algo estranho dentro", "sim", true, null),
            new RefundReason("alergia", "Tinha ingrediente que não estava informado ou causou reação", "opcional", true,
                    "Se houver reação grave, procure atendimento médico."),
            new RefundReason("desisti", "Desisti do pedido (já está em produção)", "nao", false,
                    "Como o pedido já foi feito só para você, a devolução nesse caso é improvável."),
            new RefundReason("outro", "Outro motivo", "sim", false, null));

    /** Cancelamento feito pela loja. */
    public static final List<Reason> STORE_CANCEL = List.of(
            new Reason("falta_ingrediente", "Faltou ingrediente"),
            new Reason("sem_agenda", "Não conseguimos produzir a tempo"),
            new Reason("fora_area", "Não entregamos nesse endereço"),
            new Reason("problema_producao", "Tivemos um problema na produção"),
            new Reason("pedido_cliente", "Cancelado a pedido do cliente"),
            new Reason("outro", "Outro motivo"));

    /** Motivos para negar uma devolução. */
    public static final List<Reason> DENY = List.of(
            new Reason("fora_prazo", "Fora do prazo"),
            new Reason("sem_comprovacao", "As fotos não mostram o problema"),
            new Reason("produto_conforme", "O produto estava conforme o pedido"),
            new Reason("consumido", "O produto foi consumido em maior parte"),
            new Reason("desistencia_producao", "Desistência com o pedido já produzido"),
            new Reason("outro", "Outro motivo"));

    /** Encerramento pelo sistema quando todo o valor foi devolvido. */
    public static final String FULL_REFUND_CODE = "devolucao_total";

    /** Encerramentos feitos pelo próprio sistema. */
    public static final List<Reason> SYSTEM = List.of(new Reason(FULL_REFUND_CODE, "Todo o valor foi devolvido"));

    /** Todos os rótulos, na ordem de busca do Node (o primeiro id igual vence). */
    private static final Map<String, String> LABELS = labels();

    /**
     * Classe só de constantes; não é instanciada.
     */
    private Reasons() {
    }

    /**
     * Junta os rótulos de todas as listas.
     *
     * @return id → rótulo
     */
    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        Stream.of(CANCEL, REFUND.stream().map(r -> new Reason(r.id(), r.label())).toList(), STORE_CANCEL, DENY, SYSTEM)
                .flatMap(List::stream).forEach(reason -> labels.putIfAbsent(reason.id(), reason.label()));
        return labels;
    }

    /**
     * Rótulo de qualquer motivo ({@code anyReasonLabel}).
     *
     * @param id id do motivo
     * @return o rótulo, ou o próprio id
     */
    public static String label(String id) {
        return LABELS.getOrDefault(id, id);
    }

    /**
     * Se o id está na lista.
     *
     * @param list lista
     * @param id   id (pode ser nulo ou de outro tipo)
     * @return verdadeiro se existir
     */
    public static boolean contains(List<Reason> list, Object id) {
        return list.stream().anyMatch(reason -> reason.id().equals(id));
    }

    /**
     * Motivo com rótulo.
     *
     * @param id    id
     * @param label rótulo
     */
    public record Reason(String id, String label) {
    }

    /**
     * Motivo de devolução.
     *
     * @param id       id
     * @param label    rótulo
     * @param photos   {@code sim}, {@code opcional} ou {@code nao}
     * @param priority se a dona deve ver primeiro
     * @param hint     dica ao cliente (nula = nenhuma)
     */
    public record RefundReason(String id, String label, String photos, boolean priority, String hint) {
    }
}
