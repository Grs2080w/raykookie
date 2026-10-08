package br.com.kntap.raykookie.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.orders.domain.PublicCodes;
import br.com.kntap.raykookie.orders.domain.ShopOrderForm;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Textos das linhas da tela Pedidos, motivo do aviso de cancelamento e código público
 * ({@code backend/tests/order-code.test.ts} e {@code cancel.test.ts}).
 */
class OrderTextsTest {

    /**
     * Pedido de teste.
     *
     * @param fulfillment retirada ou entrega
     * @param payment     forma de pagamento
     * @param change      troco
     * @param notes       observação
     * @return o pedido
     */
    private static ShopOrderForm form(String fulfillment, String payment, Money change, String notes) {
        ShopOrderForm.Address address = "entrega".equals(fulfillment)
                ? new ShopOrderForm.Address("01310100", "Av. Paulista", "1000", "ap 5", "Bela Vista", "São Paulo")
                : ShopOrderForm.Address.EMPTY;
        return new ShopOrderForm("Maria", "11912345678", fulfillment, address, LocalDate.parse("2026-10-05"), "tarde",
                payment, change, notes, List.of(new ShopOrderForm.Line("1", "Cookie", 2, Money.ofCents(900))),
                Money.ofCents(1800), null);
    }

    /**
     * Observação e endereço das linhas.
     */
    @Test
    @DisplayName("linha da tela Pedidos: número, telefone, pagamento com troco, observação só na primeira")
    void lineTexts() {

        // ── 1. Observação.
        ShopOrderForm cash = form("retirada", "dinheiro", Money.ofCents(5000), "sem nozes");
        assertThat(ShopOrderNotes.notes(cash, 4821, true))
                .isEqualTo("Pedido online #4821 · Tel (11) 91234-5678 · Pagamento: Dinheiro (troco p/ R$ 50,00) · "
                        + "Obs: sem nozes");
        assertThat(ShopOrderNotes.notes(cash, 4821, false))
                .isEqualTo("Pedido online #4821 · Tel (11) 91234-5678 · Pagamento: Dinheiro (troco p/ R$ 50,00)");
        assertThat(ShopOrderNotes.notes(form("retirada", "pix", null, "x".repeat(400)), 1, true)).hasSize(300);
        assertThat(ShopOrderNotes.phone("1133334444")).isEqualTo("(11) 3333-4444");

        // ── 2. Endereço.
        assertThat(ShopOrderNotes.where(form("entrega", "pix", null, ""), ""))
                .isEqualTo("Entrega: Av. Paulista, 1000 (ap 5) - Bela Vista, São Paulo");
        assertThat(ShopOrderNotes.where(form("retirada", "pix", null, ""), "Rua A, 1"))
                .isEqualTo("Retirada: Rua A, 1");
        assertThat(ShopOrderNotes.where(form("retirada", "pix", null, ""), "")).isEqualTo("Retirada no local");
    }

    /**
     * Motivo no aviso à dona.
     */
    @Test
    @DisplayName("motivo do aviso: rótulo e texto; 'outro' com texto mostra só o texto")
    void noticeReason() {
        assertThat(CustomerCancelUseCase.noticeReason("outro", "Errei o pedido")).isEqualTo("Errei o pedido");
        assertThat(CustomerCancelUseCase.noticeReason("data_horario", ""))
                .isEqualTo("A data ou o horário não servem mais");
        assertThat(CustomerCancelUseCase.noticeReason("data_horario", "viajei"))
                .isEqualTo("A data ou o horário não servem mais: viajei");
    }

    /**
     * Código público.
     */
    @Test
    @DisplayName("código público: 4 ou 5 dígitos, sem repetir, tenta de novo e desiste depois de 30")
    void publicCodes() {

        // ── 1. Sem repetição.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            int code = PublicCodes.allocate(seen::contains);
            assertThat(code).isBetween(PublicCodes.MIN, PublicCodes.MAX);
            assertThat(seen.add(code)).isTrue();
        }

        // ── 2. Tentativas.
        AtomicInteger calls = new AtomicInteger();
        PublicCodes.allocate(code -> calls.incrementAndGet() < 4);
        assertThat(calls).hasValue(4);
        assertThatThrownBy(() -> PublicCodes.allocate(code -> true)).isInstanceOf(IllegalStateException.class);
    }
}
