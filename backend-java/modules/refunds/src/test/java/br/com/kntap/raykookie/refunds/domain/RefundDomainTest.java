package br.com.kntap.raykookie.refunds.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.Money;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regras puras de devolução: espera entre tentativas, restante em centavos e o pedido do cliente.
 */
class RefundDomainTest {

    /** Itens do pedido de R$ 100. */
    private static final Map<String, RefundRequestForm.OrderedItem> ORDERED = Map.of(
            "1", new RefundRequestForm.OrderedItem("1", "Brownie", 2, Money.ofCents(3000)),
            "2", new RefundRequestForm.OrderedItem("2", "Torta", 1, Money.ofCents(4000)));

    /**
     * Soma das devoluções aceitas nunca passa do pago (T08, centavos exatos).
     *
     * @param totalCents total pago
     * @param asks       valores pedidos em sequência
     */
    @Property
    void partialRefundsNeverExceedPaid(@ForAll @LongRange(min = 1, max = 1_000_000) long totalCents,
            @ForAll @Size(max = 30) List<@LongRange(min = 1, max = 500_000) Long> asks) {
        Money total = Money.ofCents(totalCents);
        Money refunded = Money.ZERO;
        for (long ask : asks) {
            Money left = RefundAmounts.left(total, refunded, Money.ZERO);
            if (left.cents() > 0 && !left.isLessThan(Money.ofCents(ask))) {
                refunded = refunded.plus(Money.ofCents(ask));
            }
        }
        assertThat(refunded.isLessThan(total) || refunded.equals(total)).isTrue();
    }

    /**
     * Espera entre tentativas.
     *
     * @param attempts tentativas
     */
    @Property
    void retryScheduleStopsAfterEight(@ForAll @IntRange(min = -2, max = 20) int attempts) {
        assertThat(RetrySchedule.after(attempts).isPresent()).isEqualTo(attempts >= 1 && attempts <= 8);
    }

    /**
     * Espera e mensagens.
     */
    @Test
    @DisplayName("15 min, 1 h, 3 h, 12 h…; restante e a mensagem em reais")
    void scheduleAndMessages() {
        assertThat(RetrySchedule.after(1)).contains(Duration.ofMinutes(15));
        assertThat(RetrySchedule.after(4)).contains(Duration.ofHours(12));
        assertThat(RetrySchedule.after(9)).isEmpty();
        assertThat(RefundAmounts.left(Money.ofCents(10_000), Money.ofCents(2500), Money.ofCents(1000)))
                .isEqualTo(Money.ofCents(6500));
        assertThat(RefundAmounts.tooMuch(Money.ofCents(123_456)))
                .isEqualTo("O valor passa do que ainda pode ser devolvido (R$ 1234,56).");
    }

    /**
     * Pedido do cliente.
     */
    @Test
    @DisplayName("pedido: motivo, descrição, fotos, itens marcados e o valor pedido")
    void form() {

        // ── 1. Validações.
        String text = "O brownie chegou seco e duro, não dá para comer.";
        assertThatThrownBy(() -> RefundRequestForm.of("xxx", text, List.of(), List.of()))
                .hasMessage("Escolha o motivo da devolução.");
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", "  curto  ", List.of(), List.of()))
                .hasMessageContaining("pelo menos 20");
        byte[] big = new byte[2_000_001];
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", text, List.of(),
                List.of(new RefundRequestForm.Photo("image/png", big)))).isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.httpStatus()).isEqualTo(413));

        // ── 2. Itens: existentes, quantidade de 1 até o pedido; o valor soma.
        RefundRequestForm ok = RefundRequestForm.of("qualidade", text + "\n\n  repetido ", List.of(
                Map.of("id", "1", "qty", "2.9"), Map.of("id", 2, "qty", 1)), List.of());
        assertThat(ok.description()).endsWith("comer. repetido");
        RefundRequestForm.Picked picked = ok.pick(ORDERED);
        assertThat(picked.requested()).isEqualTo(Money.ofCents(10_000));
        assertThat(picked.items()).extracting(RefundRequestForm.PickedItem::qty).containsExactly(2L, 1L);
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", text, List.of(Map.of("id", "1", "qty", 3)),
                List.of()).pick(ORDERED)).hasMessageContaining("passa do que foi pedido");
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", text, List.of(Map.of("id", "9", "qty", 1)),
                List.of()).pick(ORDERED)).hasMessageContaining("passa do que foi pedido");
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", text, List.of(Map.of("id", "1", "qty", "x")),
                List.of()).pick(ORDERED)).hasMessageContaining("passa do que foi pedido");
        assertThatThrownBy(() -> RefundRequestForm.of("qualidade", text, "nao-lista", List.of()).pick(ORDERED))
                .hasMessage("Marque pelo menos um item com problema.");
    }
}
