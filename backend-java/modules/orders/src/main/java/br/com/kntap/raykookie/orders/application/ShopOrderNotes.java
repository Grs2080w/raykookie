package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Textos das linhas que um pedido da loja cria na tela Pedidos ({@code createShopOrder}).
 */
final class ShopOrderNotes {

    /** Separador das partes da observação. */
    private static final String SEPARATOR = " · ";

    /** Tamanho máximo da observação. */
    private static final int MAX_NOTES = 300;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private ShopOrderNotes() {
    }

    /**
     * Endereço da linha: entrega, retirada com o endereço da loja ou "Retirada no local".
     *
     * @param form   pedido
     * @param pickup endereço de retirada congelado (vazio = nenhum)
     * @return o texto
     */
    static String where(ShopOrderForm form, String pickup) {
        ShopOrderForm.Address a = form.address();
        if ("entrega".equals(form.fulfillment())) {
            String complement = a.complement().isEmpty() ? "" : " (" + a.complement() + ")";
            return "Entrega: " + a.street() + ", " + a.number() + complement + " - " + a.district() + ", " + a.city();
        }
        return pickup.isEmpty() ? "Retirada no local" : "Retirada: " + pickup;
    }

    /**
     * Observação da linha: número, telefone, pagamento (com troco) e, na primeira, a observação do cliente.
     *
     * @param form  pedido
     * @param code  número que o cliente vê
     * @param first se é a primeira linha
     * @return o texto (até 300)
     */
    static String notes(ShopOrderForm form, int code, boolean first) {

        // ── 1. Pagamento, com o troco no formato do toFixed(2).
        String change = form.changeFor() == null || form.changeFor().isZero() ? ""
                : " (troco p/ R$ " + form.changeFor().toDecimal().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                        .replace('.', ',') + ")";

        // ── 2. Partes não vazias, juntas.
        List<String> parts = new ArrayList<>(List.of("Pedido online #" + code, "Tel " + phone(form.phone()),
                "Pagamento: " + ShopOrderForm.PAYMENTS.get(form.paymentMethod()) + change));
        if (first && form.coupon() != null) {
            parts.add("Cupom " + form.coupon().code() + " (-" + form.coupon().percent().stripTrailingZeros()
                    .toPlainString().replace('.', ',') + "%): -" + form.coupon().discount().toBrl());
        }
        if (first && !form.notes().isEmpty()) {
            parts.add("Obs: " + form.notes());
        }
        return NodeInput.cut(String.join(SEPARATOR, parts), MAX_NOTES);
    }

    /**
     * Telefone formatado ({@code fmtPhone}).
     *
     * @param digits 10 ou 11 dígitos
     * @return {@code (11) 91234-5678} ou {@code (11) 1234-5678}
     */
    static String phone(String digits) {
        int split = digits.length() == 11 ? 7 : 6;
        return "(" + digits.substring(0, 2) + ") " + digits.substring(2, split) + "-" + digits.substring(split);
    }
}
