package br.com.kntap.raykookie.notifications.domain;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Os textos das notificações, idênticos aos do Node ({@code backend/lib/push.ts}): título, corpo, dados e
 * canal de som de cada aviso.
 */
public final class Messages {

    /** Canal único do app do cliente ("Seus pedidos"). */
    public static final String CUSTOMER_CHANNEL = "pedidos_cliente";

    /** Som do pagamento confirmado (canal que todo app de gestão já tem). */
    private static final String PAID_SOUND = "caixa";

    /** Som de alerta (cancelamento, devolução, privacidade, preço). */
    private static final String ALERT_SOUND = "alerta";

    /** Prazo da resposta de privacidade, em dias. */
    private static final int PRIVACY_DEADLINE_DAYS = 15;

    /** Rótulos dos tipos de solicitação de privacidade ({@code privacyLabel} do Node). */
    private static final Map<String, String> PRIVACY_LABELS = Map.of(
            "anonimizacao", "Anonimizar, bloquear ou eliminar dados",
            "acesso", "Quero uma resposta sobre os meus dados",
            "compartilhamento", "Saber com quem compartilharam meus dados",
            "revogacao", "Revogar um consentimento",
            "outro", "Outro assunto",
            "exclusao", "Exclusão da conta",
            "correcao", "Correção de dados",
            "portabilidade", "Cópia dos dados");

    /** Texto do pedido pago entregue (lembra a devolução em 48 h). */
    private static final String DELIVERED_PAID =
            "Bom apetite! Se algo não estiver certo, você pode pedir devolução em até 48h pelo app.";

    /** Período do dia, como o cliente lê. */
    private static final Map<String, String> WINDOW = Map.of("manha", "da manhã", "tarde", "da tarde", "noite",
            "da noite");

    /**
     * Sem instâncias.
     */
    private Messages() {
    }

    /**
     * Valor como o {@code brl} do Node: {@code R$ 1234,50} (duas casas, vírgula, sem separador de milhar).
     *
     * @param money valor
     * @return o texto
     */
    public static String brl(Money money) {
        return "R$ " + money.toDecimal().setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Aviso de pedido novo, no som escolhido pelo aparelho.
     *
     * @param token token
     * @param sound som do aparelho
     * @param o     pedido
     * @return a mensagem
     */
    public static PushMessage newOrder(String token, String sound, OwnerNotice.NewOrder o) {
        String channel = OrderSound.channel(sound);
        return new PushMessage(token, "Novo pedido #" + codeOrId(o.code(), o.orderId()),
                o.customer() + " · " + brl(o.total()) + " · " + items(o.items()),
                Map.of("type", "novo_pedido", "orderId", String.valueOf(o.orderId())), channel, channel);
    }

    /**
     * Aviso de pagamento confirmado.
     *
     * @param token token
     * @param o     pedido
     * @return a mensagem
     */
    public static PushMessage paid(String token, OwnerNotice.Paid o) {
        return owner(token, "Pix recebido · Pedido #" + codeOrId(o.code(), o.orderId()),
                o.customer() + " pagou " + brl(o.total()) + " · " + items(o.items()),
                Map.of("type", "pagamento", "orderId", String.valueOf(o.orderId())), PAID_SOUND);
    }

    /**
     * Aviso de pedido cancelado pela cliente.
     *
     * @param token token
     * @param o     pedido
     * @return a mensagem
     */
    public static PushMessage cancelled(String token, OwnerNotice.Cancelled o) {
        String why = o.reason() == null || o.reason().isEmpty() ? "" : " · " + cut(o.reason(), 80);
        String refunded = o.refunded() == null || o.refunded().isZero()
                ? ""
                : ". O Pix de " + brl(o.refunded()) + " foi devolvido automaticamente.";
        return owner(token, "Pedido #" + codeOrId(o.code(), o.orderId()) + " cancelado pela cliente",
                o.customer() + " cancelou · " + brl(o.total()) + why + refunded,
                Map.of("type", "pedido_cancelado", "orderId", String.valueOf(o.orderId())), ALERT_SOUND);
    }

    /**
     * Aviso de solicitação de privacidade.
     *
     * @param token token
     * @param o     solicitação
     * @return a mensagem
     */
    public static PushMessage privacy(String token, OwnerNotice.PrivacyRequest o) {
        return owner(token, "Solicitação de privacidade", PRIVACY_LABELS.getOrDefault(o.type(), o.type())
                        + ". Responda em até " + PRIVACY_DEADLINE_DAYS + " dias.",
                Map.of("type", "privacidade", "requestId", String.valueOf(o.requestId())), ALERT_SOUND);
    }

    /**
     * Aviso de devolução para a dona.
     *
     * @param token token
     * @param o     devolução
     * @return a mensagem
     */
    public static PushMessage refund(String token, OwnerNotice.Refund o) {
        Map<String, String> data = Map.of("type", "devolucao", "refundId", String.valueOf(o.refundId()));
        String again = o.stalled() ? "Toque para tentar de novo." : "Tentamos de novo mais tarde.";
        return switch (o.kind()) {
            case "solicitada" -> owner(token, (o.priority() ? "Urgente: " : "") + "Pedido de devolução #" + o.code(),
                    refundRequestBody(o), data, ALERT_SOUND);
            case "desistiu" -> owner(token, "Cliente desistiu da devolução #" + o.code(),
                    o.customer() + " cancelou a solicitação antes da sua análise.", data, ALERT_SOUND);
            case "concluida" -> owner(token, "Devolução concluída #" + o.code(),
                    brl(o.amount()) + " devolvidos depois da nova tentativa.", data, ALERT_SOUND);
            case "saldo" -> owner(token, "Devolução parada: falta saldo", "Pedido #" + o.code() + ": coloque "
                    + brl(o.amount()) + " no Mercado Pago. " + again, data, ALERT_SOUND);
            case "falha" -> owner(token, "Devolução não concluída", "Pedido #" + o.code() + " (" + brl(o.amount())
                    + "). " + again, data, ALERT_SOUND);
            default -> owner(token, "Pix pago depois do cancelamento #" + o.code(),
                    "Devolvemos o valor automaticamente ao cliente.", data, ALERT_SOUND);
        };
    }

    /**
     * Texto do pedido de devolução.
     *
     * @param o devolução
     * @return o texto
     */
    private static String refundRequestBody(OwnerNotice.Refund o) {
        String reason = o.reason() == null || o.reason().isEmpty()
                ? ""
                : ": " + o.reason().substring(0, 1).toLowerCase() + o.reason().substring(1);
        String plural = o.photos() > 1 ? "s" : "";
        String photos = o.photos() > 0 ? " " + o.photos() + " foto" + plural + "." : "";
        return o.customer() + " pede " + brl(o.amount()) + " de volta" + reason + "." + photos + " Toque para decidir.";
    }

    /**
     * Alerta de preço ou margem.
     *
     * @param token token
     * @param o     alerta
     * @return a mensagem
     */
    public static PushMessage priceAlert(String token, OwnerNotice.PriceAlert o) {
        boolean margin = "margem".equals(o.kind());
        String body = margin
                ? "A margem de " + o.name() + " caiu de " + oneDecimal(o.before()) + "% para " + oneDecimal(o.after())
                        + "%. " + o.cause()
                : "O preço de " + o.name() + " sobe de " + brl(reais(o.before())) + " para " + brl(reais(o.after()))
                        + ". " + o.cause();
        return owner(token, margin ? "Margem caiu" : "Preço do cardápio vai subir", body,
                Map.of("type", "preco", "recipeId", String.valueOf(o.recipeId())), ALERT_SOUND);
    }

    /**
     * Aviso para o cliente, se aquele status tiver aviso.
     *
     * @param token token
     * @param n     aviso
     * @return a mensagem, ou vazio (status sem aviso)
     */
    public static Optional<PushMessage> customer(String token, CustomerNotice n) {
        CustomerNotice.OrderRef o = n.order();
        return switch (n) {
            case CustomerNotice.Paid p -> Optional.of(customerMessage(token, o, "pagamento",
                    "Pagamento confirmado · #" + o.code(), "Recebemos o pagamento de " + brl(o.total())
                            + ". Seu pedido entra na fila de produção."));
            case CustomerNotice.Status s -> status(token, o, s.status());
            case CustomerNotice.StoreCancel c -> Optional.of(customerMessage(token, o, "cancelado",
                    "Pedido cancelado pela loja · #" + o.code(), "Motivo: " + c.reason() + "."
                            + (c.refunded().isZero() ? "" : " " + brl(c.refunded()) + " voltam por Pix.")));
            case CustomerNotice.Refund r -> Optional.of(customerRefund(token, o, r));
        };
    }

    /**
     * Mudança de etapa do pedido.
     *
     * @param token  token
     * @param o      pedido
     * @param status status
     * @return a mensagem, ou vazio
     */
    private static Optional<PushMessage> status(String token, CustomerNotice.OrderRef o, String status) {
        String window = WINDOW.getOrDefault(o.timeWindow() == null ? "" : o.timeWindow(), "combinado");
        return switch (status) {
            case "producao" -> Optional.of(customerMessage(token, o, "status", "Pedido em preparo · #" + o.code(),
                    "Começamos a preparar o seu pedido."));
            case "pronto" -> Optional.of(customerMessage(token, o, "status", "Pedido pronto · #" + o.code(),
                    "entrega".equals(o.fulfillment())
                            ? "Ele sai para entrega no período " + window + "."
                            : "Pode retirar no endereço combinado, no período " + window + "."));
            case "entregue" -> Optional.of(customerMessage(token, o, "status", "Pedido entregue · #" + o.code(),
                    o.paid() ? DELIVERED_PAID : "Bom apetite! Obrigada pela encomenda."));
            default -> Optional.empty();
        };
    }

    /**
     * Novidade da devolução para o cliente.
     *
     * @param token token
     * @param o     pedido
     * @param r     devolução
     * @return a mensagem
     */
    private static PushMessage customerRefund(String token, CustomerNotice.OrderRef o, CustomerNotice.Refund r) {
        return switch (r.kind()) {
            case "aprovada" -> customerMessage(token, o, "devolucao", "Devolução aprovada · #" + o.code(),
                    r.amount().isLessThan(r.requested())
                            ? brl(r.amount()) + " de " + brl(r.requested()) + ". Veja a mensagem da loja."
                            : brl(r.amount()) + " voltam por Pix.");
            case "negada" -> customerMessage(token, o, "devolucao", "Devolução negada · #" + o.code(),
                    "Motivo: " + r.reason() + ".");
            default -> customerMessage(token, o, "devolucao", "Pix devolvido · #" + o.code(),
                    brl(r.amount()) + " voltaram para a conta que pagou.");
        };
    }

    /**
     * Mensagem para o app do cliente (canal único, som padrão do celular).
     *
     * @param token token
     * @param o     pedido
     * @param type  tipo
     * @param title título
     * @param body  texto
     * @return a mensagem
     */
    private static PushMessage customerMessage(String token, CustomerNotice.OrderRef o, String type, String title,
            String body) {
        return new PushMessage(token, title, body,
                Map.of("type", type, "orderId", String.valueOf(o.id()), "audience", "cliente"),
                CUSTOMER_CHANNEL, "default");
    }

    /**
     * Mensagem para o app de gestão num canal de som fixo.
     *
     * @param token token
     * @param title título
     * @param body  texto
     * @param data  dados
     * @param sound som
     * @return a mensagem
     */
    private static PushMessage owner(String token, String title, String body, Map<String, String> data, String sound) {
        return new PushMessage(token, title, body, data, OrderSound.channel(sound), "pedido_" + sound);
    }

    /**
     * "1 item" ou "N itens".
     *
     * @param count quantidade
     * @return o texto
     */
    private static String items(int count) {
        return count + " " + (count == 1 ? "item" : "itens");
    }

    /**
     * O código público, ou o id se não houver.
     *
     * @param code código
     * @param id   id
     * @return o texto
     */
    private static String codeOrId(Integer code, long id) {
        return String.valueOf(code == null ? id : code);
    }

    /**
     * Corta no tamanho.
     *
     * @param text texto
     * @param max  tamanho
     * @return o texto cortado
     */
    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Uma casa decimal com vírgula, como {@code String(Math.round(n * 10) / 10).replace('.', ',')}.
     *
     * @param value número
     * @return o texto
     */
    static String oneDecimal(double value) {
        double rounded = Math.floor(value * 10 + 0.5) / 10;
        String text = rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
        return text.replace('.', ',');
    }

    /**
     * Reais (double) em dinheiro, arredondando ao centavo.
     *
     * @param value reais
     * @return o valor
     */
    private static Money reais(double value) {
        return Money.fromDecimal(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
    }
}
