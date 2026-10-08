package br.com.kntap.raykookie.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Se o pagamento com cartão está disponível agora. Só fica ligado quando a configuração pede
 * ({@code PAYMENTS_CARD_ENABLED=true}) <b>e</b> o Mercado Pago aceitou as credenciais na subida
 * ({@link MercadoPagoEnvironmentCheck}). O {@code /api/payment-config} lê daqui: com o cartão desligado,
 * o site e o app escondem a opção em vez de falhar no pagamento (G31).
 */
@Component
public class CardPaymentsStatus {

    /** Estado atual; começa desligado até a conferência terminar bem. */
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    /**
     * Diz se o cartão está disponível.
     *
     * @return verdadeiro se o cartão pode ser oferecido
     */
    public boolean enabled() {
        return enabled.get();
    }

    /**
     * Liga ou desliga o cartão.
     *
     * @param value o novo estado
     */
    void set(boolean value) {
        enabled.set(value);
    }
}
