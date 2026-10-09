package br.com.kntap.raykookie.refunds.domain;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.orders.api.rules.RefundRules;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pedido de devolução do cliente ({@code requestRefund}): motivo da lista, descrição de pelo menos 20 caracteres,
 * até 5 fotos de até 2 MB e os itens marcados, que precisam existir no pedido.
 *
 * @param reason      motivo
 * @param description descrição (espaços juntados, até 1000)
 * @param photos      fotos já conferidas
 * @param rawItems    itens marcados como vieram ({@code [{id, qty}]})
 */
public record RefundRequestForm(Reasons.RefundReason reason, String description, List<Photo> photos,
        Object rawItems) {

    /**
     * Confere o que não depende do pedido.
     *
     * @param reasonCode  motivo
     * @param description descrição
     * @param items       itens marcados
     * @param photos      fotos
     * @return o pedido
     */
    public static RefundRequestForm of(Object reasonCode, Object description, Object items, List<Photo> photos) {

        // ── 1. Motivo e descrição.
        Reasons.RefundReason reason = Reasons.REFUND.stream().filter(r -> r.id().equals(reasonCode)).findFirst()
                .orElseThrow(() -> new InvalidInputException("Escolha o motivo da devolução."));
        String text = NodeInput.cut(NodeInput.text(description).replaceAll("\\s+", " "), 1000);
        if (text.length() < RefundRules.MIN_REFUND_DESCRIPTION) {
            throw new InvalidInputException("Conte o que aconteceu (pelo menos " + RefundRules.MIN_REFUND_DESCRIPTION
                    + " caracteres).");
        }

        // ── 2. Fotos.
        if (photos.size() > RefundRules.MAX_REFUND_PHOTOS) {
            throw new InvalidInputException("Envie no máximo " + RefundRules.MAX_REFUND_PHOTOS + " fotos.");
        }
        if (photos.stream().anyMatch(p -> p.data().length > RefundRules.MAX_REFUND_PHOTO_BYTES)) {
            throw new DomainException("Uma das fotos é grande demais (máximo de 2 MB).", 413);
        }
        return new RefundRequestForm(reason, text, photos, items);
    }

    /**
     * Itens marcados conferidos contra os do pedido.
     *
     * @param ordered itens do pedido ({@code id} → item)
     * @return os itens e o valor pedido (sem limite)
     */
    public Picked pick(Map<String, OrderedItem> ordered) {
        List<PickedItem> picked = new ArrayList<>();
        Money requested = Money.ZERO;
        for (Object raw : rawItems instanceof List<?> list ? list : List.of()) {
            Map<?, ?> line = raw instanceof Map<?, ?> map ? map : Map.of();
            OrderedItem item = ordered.get(NodeInput.text(line.get("id")));
            Optional<Long> qty = NodeInput.floor(line.get("qty"));
            if (item == null || qty.isEmpty() || qty.get() < 1 || qty.get() > item.qty()) {
                throw new InvalidInputException("Confira os itens marcados: a quantidade passa do que foi pedido.");
            }
            picked.add(new PickedItem(item.id(), item.name(), qty.get()));
            requested = requested.plus(item.unitPrice().times(qty.get()));
        }
        if (picked.isEmpty()) {
            throw new InvalidInputException("Marque pelo menos um item com problema.");
        }
        return new Picked(picked, requested);
    }

    /**
     * Foto conferida.
     *
     * @param type tipo
     * @param data bytes (JPEG já sem metadados)
     */
    public record Photo(String type, byte[] data) {
    }

    /**
     * Item do pedido.
     *
     * @param id        id
     * @param name      nome
     * @param qty       quantidade pedida
     * @param unitPrice preço
     */
    public record OrderedItem(String id, String name, long qty, Money unitPrice) {
    }

    /**
     * Item marcado.
     *
     * @param id   id
     * @param name nome
     * @param qty  quantidade
     */
    public record PickedItem(String id, String name, long qty) {
    }

    /**
     * Itens marcados e quanto valem.
     *
     * @param items     itens
     * @param requested valor
     */
    public record Picked(List<PickedItem> items, Money requested) {
    }
}
