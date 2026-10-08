package br.com.kntap.raykookie.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.notifications.domain.Messages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Casos restantes das mensagens: devolução desistida, concluída, falha e cancelamento sem motivo.
 */
class ProfileAndTextTest {

    /**
     * Os textos de devolução que faltavam.
     */
    @Test
    @DisplayName("devolução: desistiu, concluída, falha; cancelamento sem motivo")
    void remainingTexts() {
        OwnerId owner = new OwnerId(1);
        assertThat(Messages.refund("t", new OwnerNotice.Refund(owner, "desistiu", 1, 10, "Ana", Money.ofCents(1),
                false, null, 0, false)).body()).isEqualTo("Ana cancelou a solicitação antes da sua análise.");
        assertThat(Messages.refund("t", new OwnerNotice.Refund(owner, "concluida", 1, 10, null, Money.ofCents(990),
                false, null, 0, false)).body()).isEqualTo("R$ 9,90 devolvidos depois da nova tentativa.");
        assertThat(Messages.refund("t", new OwnerNotice.Refund(owner, "falha", 1, 10, null, Money.ofCents(990),
                false, null, 0, false)).body()).isEqualTo("Pedido #10 (R$ 9,90). Tentamos de novo mais tarde.");
        assertThat(Messages.cancelled("t", new OwnerNotice.Cancelled(owner, 3, null, "Ana", Money.ofCents(500), "",
                Money.ofCents(0))).body()).isEqualTo("Ana cancelou · R$ 5,00");
    }
}
