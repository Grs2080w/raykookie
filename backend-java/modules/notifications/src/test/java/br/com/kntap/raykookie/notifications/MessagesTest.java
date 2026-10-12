package br.com.kntap.raykookie.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.kernel.events.AppReleased;
import br.com.kntap.raykookie.notifications.api.events.CustomerNotice;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.notifications.domain.Messages;
import br.com.kntap.raykookie.notifications.domain.OrderSound;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Textos idênticos aos do Node (vetores de {@code backend/tests/push.test.ts} e de {@code backend/lib/push.ts}).
 */
class MessagesTest {

    /** A dona. */
    private static final OwnerId OWNER = new OwnerId(1);

    /** Pedido de referência do teste do Node: {id: 7, customer: 'Joana', total: 45.5, items: 3}. */
    private static final OwnerNotice.NewOrder ORDER =
            new OwnerNotice.NewOrder(OWNER, 7, null, "Joana", Money.ofCents(4550), 3);

    /** Pedido do cliente. */
    private static final CustomerNotice.OrderRef REF =
            new CustomerNotice.OrderRef(9, 4321, new CustomerId(5), Money.ofCents(12000), "entrega", "tarde", true);

    /**
     * Novo pedido: título, texto, canal e dados.
     */
    @Test
    @DisplayName("novo pedido: título, texto, canal do som e dados")
    void newOrder() {
        PushMessage m = Messages.newOrder("tok", "caixa", ORDER);
        assertThat(m.title()).isEqualTo("Novo pedido #7");
        assertThat(m.body()).isEqualTo("Joana · R$ 45,50 · 3 itens");
        assertThat(m.channelId()).isEqualTo("pedido_caixa");
        assertThat(m.sound()).isEqualTo("pedido_caixa");
        assertThat(m.data()).isEqualTo(Map.of("type", "novo_pedido", "orderId", "7"));
    }

    /**
     * Singular e som desconhecido.
     */
    @Test
    @DisplayName("1 item no singular; som desconhecido cai no padrão")
    void singularAndUnknownSound() {
        PushMessage m = Messages.newOrder("tok", "inexistente",
                new OwnerNotice.NewOrder(OWNER, 7, 1234, "Joana", Money.ofCents(4550), 1));
        assertThat(m.body()).contains("1 item");
        assertThat(m.title()).isEqualTo("Novo pedido #1234");
        assertThat(m.channelId()).isEqualTo("pedido_sino");
        assertThat(OrderSound.isValid("alerta")).isTrue();
        assertThat(OrderSound.isValid("../etc")).isFalse();
        assertThat(OrderSound.isValid(null)).isFalse();
    }

    /**
     * Pagamento, cancelamento e privacidade.
     */
    @Test
    @DisplayName("pagamento, cancelamento com devolução e privacidade")
    void ownerMessages() {

        // ── 1. Pagamento no canal caixa.
        PushMessage paid = Messages.paid("tok", new OwnerNotice.Paid(OWNER, 7, null, "Joana", Money.ofCents(4550), 3));
        assertThat(paid.title()).isEqualTo("Pix recebido · Pedido #7");
        assertThat(paid.body()).isEqualTo("Joana pagou R$ 45,50 · 3 itens");
        assertThat(paid.channelId()).isEqualTo("pedido_caixa");

        // ── 2. Cancelamento com motivo e devolução.
        PushMessage cancel = Messages.cancelled("tok", new OwnerNotice.Cancelled(OWNER, 7, 1500, "Joana",
                Money.ofCents(123450), "Mudei de ideia", Money.ofCents(123450)));
        assertThat(cancel.title()).isEqualTo("Pedido #1500 cancelado pela cliente");
        assertThat(cancel.body()).isEqualTo("Joana cancelou · R$ 1234,50 · Mudei de ideia. O Pix de R$ 1234,50 foi "
                + "devolvido automaticamente.");
        assertThat(cancel.channelId()).isEqualTo("pedido_alerta");

        // ── 3. Privacidade.
        PushMessage privacy = Messages.privacy("tok", new OwnerNotice.PrivacyRequest(OWNER, 3, "revogacao"));
        assertThat(privacy.body()).isEqualTo("Revogar um consentimento. Responda em até 15 dias.");
        assertThat(privacy.data()).isEqualTo(Map.of("type", "privacidade", "requestId", "3"));
    }

    /**
     * Devoluções para a dona.
     */
    @Test
    @DisplayName("devoluções: solicitada urgente com fotos, saldo e tardio")
    void refunds() {
        PushMessage asked = Messages.refund("tok", new OwnerNotice.Refund(OWNER, "solicitada", 4, 1500, "Joana",
                Money.ofCents(2000), true, "Veio errado", 2, false));
        assertThat(asked.title()).isEqualTo("Urgente: Pedido de devolução #1500");
        assertThat(asked.body()).isEqualTo("Joana pede R$ 20,00 de volta: veio errado. 2 fotos. Toque para decidir.");
        PushMessage balance = Messages.refund("tok", new OwnerNotice.Refund(OWNER, "saldo", 4, 1500, null,
                Money.ofCents(2000), false, null, 0, true));
        assertThat(balance.body()).isEqualTo("Pedido #1500: coloque R$ 20,00 no Mercado Pago. Toque para tentar de novo.");
        assertThat(Messages.refund("tok", new OwnerNotice.Refund(OWNER, "tardio", 4, 1500, null, Money.ofCents(1),
                false, null, 0, false)).title()).isEqualTo("Pix pago depois do cancelamento #1500");
    }

    /**
     * Alertas de preço e margem, com uma casa decimal.
     */
    @Test
    @DisplayName("alerta de margem e de preço")
    void priceAlerts() {
        PushMessage margin = Messages.priceAlert("tok", new OwnerNotice.PriceAlert(OWNER, 2, "Bolo", "margem", 42.36,
                30, "Farinha subiu."));
        assertThat(margin.title()).isEqualTo("Margem caiu");
        assertThat(margin.body()).isEqualTo("A margem de Bolo caiu de 42,4% para 30%. Farinha subiu.");
        PushMessage price = Messages.priceAlert("tok", new OwnerNotice.PriceAlert(OWNER, 2, "Bolo", "preco", 50, 55.5,
                "Farinha subiu."));
        assertThat(price.body()).isEqualTo("O preço de Bolo sobe de R$ 50,00 para R$ 55,50. Farinha subiu.");
    }

    /**
     * Alerta de fraude: título com o número do pedido e orientação de não entregar.
     */
    @Test
    @DisplayName("alerta de fraude: pedido, cliente, valor e 'não entregue'")
    void fraudAlert() {
        PushMessage fraud = Messages.fraudAlert("tok", new OwnerNotice.FraudAlert(OWNER, 7, 1234, "Ana",
                Money.ofCents(6400)));
        assertThat(fraud.title()).isEqualTo("Alerta de fraude · Pedido #1234");
        assertThat(fraud.body()).contains("Ana").contains("R$ 64,00").contains("Não entregue o pedido");
        assertThat(fraud.data()).containsEntry("type", "alerta_fraude").containsEntry("orderId", "7");
    }

    /**
     * Avisos ao cliente, com o canal único.
     */
    @Test
    @DisplayName("cliente: pago, cartão recusado depois da análise, pronto para entrega, entregue pago, sem aviso para "
            + "'novo', devolução parcial")
    void customerMessages() {
        PushMessage paid = Messages.customer("tok", new CustomerNotice.Paid(REF)).orElseThrow();
        assertThat(paid.title()).isEqualTo("Pagamento confirmado · #4321");
        assertThat(paid.body()).isEqualTo("Recebemos o pagamento de R$ 120,00. Seu pedido entra na fila de produção.");
        assertThat(paid.channelId()).isEqualTo("pedidos_cliente");
        assertThat(paid.data()).isEqualTo(Map.of("type", "pagamento", "orderId", "9", "audience", "cliente"));
        PushMessage refused = Messages.customer("tok", new CustomerNotice.CardRefused(REF, "Saldo ou limite "
                + "insuficiente.")).orElseThrow();
        assertThat(refused.title()).isEqualTo("Cartão não aprovado · #4321");
        assertThat(refused.body()).isEqualTo("Nada foi cobrado. Saldo ou limite insuficiente. Abra o pedido para "
                + "tentar outro cartão.");
        assertThat(refused.data()).isEqualTo(Map.of("type", "pagamento", "orderId", "9", "audience", "cliente"));
        assertThat(Messages.customer("tok", new CustomerNotice.Status(REF, "pronto")).orElseThrow().body())
                .isEqualTo("Ele sai para entrega no período da tarde.");
        assertThat(Messages.customer("tok", new CustomerNotice.Status(REF, "entregue")).orElseThrow().body())
                .startsWith("Bom apetite! Se algo não estiver certo");
        assertThat(Messages.customer("tok", new CustomerNotice.Status(REF, "novo"))).isEmpty();
        assertThat(Messages.customer("tok", new CustomerNotice.Refund(REF, "aprovada", Money.ofCents(500),
                Money.ofCents(1000), null, false)).orElseThrow().body())
                .isEqualTo("R$ 5,00 de R$ 10,00. Veja a mensagem da loja.");
        assertThat(Messages.customer("tok", new CustomerNotice.StoreCancel(REF, "Sem ingrediente", Money.ofCents(12000),
                false)).orElseThrow().body()).isEqualTo("Motivo: Sem ingrediente. R$ 120,00 voltam por Pix.");
    }

    /**
     * Pago com cartão, a devolução é estorno no cartão (o Mercado Pago devolve na fatura), nunca "Pix".
     */
    @Test
    @DisplayName("devolução de pedido pago com cartão fala em estorno no cartão")
    void cardRefundIsChargeback() {

        // ── 1. Aprovada e concluída.
        assertThat(Messages.customer("tok", new CustomerNotice.Refund(REF, "aprovada", Money.ofCents(1000),
                Money.ofCents(1000), null, true)).orElseThrow().body())
                .isEqualTo("R$ 10,00 voltam como estorno no cartão.");
        PushMessage done = Messages.customer("tok", new CustomerNotice.Refund(REF, "concluida", Money.ofCents(1000),
                null, null, true)).orElseThrow();
        assertThat(done.title()).startsWith("Estorno no cartão");
        assertThat(done.body()).doesNotContain("Pix").contains("estornados no cartão");

        // ── 2. Cancelado pela loja.
        assertThat(Messages.customer("tok", new CustomerNotice.StoreCancel(REF, "Sem ingrediente", Money.ofCents(12000),
                true)).orElseThrow().body())
                .isEqualTo("Motivo: Sem ingrediente. R$ 120,00 voltam como estorno no cartão.");
    }

    /**
     * Versão nova.
     */
    @Test
    @DisplayName("versão nova: título, texto com a versão e dados para o app abrir e atualizar")
    void appUpdateMessage() {
        PushMessage m = Messages.appUpdate("tok", new AppReleased("client", "1.0.0", 130));
        assertThat(m.title()).isEqualTo("Nova versão do app");
        assertThat(m.body()).isEqualTo("Toque para atualizar para a versão 1.0.0 (build 130).");
        assertThat(m.channelId()).isEqualTo("atualizacoes");
        assertThat(m.data()).isEqualTo(Map.of("type", "update", "app", "client", "build", "130", "audience",
                "cliente"));
        assertThat(Messages.appUpdate("tok", new AppReleased("server", "1.1.0", 9)).data()).containsEntry("audience",
                "dona");
    }
}
