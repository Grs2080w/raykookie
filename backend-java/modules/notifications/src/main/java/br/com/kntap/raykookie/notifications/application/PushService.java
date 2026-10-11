package br.com.kntap.raykookie.notifications.application;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.application.ports.Devices;
import br.com.kntap.raykookie.notifications.application.ports.Devices.OwnerDevice;
import br.com.kntap.raykookie.notifications.application.ports.PushSender;
import br.com.kntap.raykookie.notifications.application.ports.PushSender.PushResult;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manda um aviso para todos os aparelhos de alguém e apaga os que o Firebase disse que não existem mais.
 * Nunca lança: falha de push não pode derrubar nada.
 */
@Service
public class PushService {

    /** Log das falhas. */
    private static final Logger LOG = LoggerFactory.getLogger(PushService.class);

    /** Aparelhos. */
    private final Devices devices;

    /** Envio. */
    private final PushSender sender;

    /**
     * Cria o serviço.
     *
     * @param devices aparelhos
     * @param sender  envio
     */
    public PushService(Devices devices, PushSender sender) {
        this.devices = devices;
        this.sender = sender;
    }

    /**
     * Avisa os celulares da dona.
     *
     * @param owner dona
     * @param build monta a mensagem de cada aparelho (com o som dele)
     * @param what  o que é o aviso (para o log)
     */
    public void toOwner(OwnerId owner, Function<OwnerDevice, PushMessage> build, String what) {
        try {
            List<OwnerDevice> all = devices.ofOwner(owner);
            if (!all.isEmpty()) {
                sender.send(all.stream().map(build).toList())
                        .ifPresent(results -> forget(results, devices::forgetOwnerTokens));
            }
        } catch (RuntimeException failure) {
            LOG.error("Falha ao avisar a dona ({})", what, failure);
        }
    }

    /**
     * Avisa os celulares de um cliente.
     *
     * @param customer cliente
     * @param build    monta a mensagem de cada aparelho (vazio = aviso sem texto para este caso)
     * @param what     o que é o aviso
     */
    public void toCustomer(CustomerId customer, Function<String, Optional<PushMessage>> build, String what) {
        try {
            List<PushMessage> messages = devices.ofCustomer(customer).stream()
                    .map(build).flatMap(Optional::stream).toList();
            if (!messages.isEmpty()) {
                sender.send(messages).ifPresent(results -> forget(results, devices::forgetCustomerTokens));
            }
        } catch (RuntimeException failure) {
            LOG.error("Falha ao avisar o cliente ({})", what, failure);
        }
    }

    /**
     * Avisa todos os aparelhos de um app (versão nova publicada).
     *
     * @param owners verdadeiro para os aparelhos das donas (app de gestão); falso para os dos clientes
     * @param build  monta a mensagem de cada aparelho, pelo token
     * @param what   o que é o aviso (para o log)
     */
    public void toEveryone(boolean owners, Function<String, PushMessage> build, String what) {
        try {
            List<String> tokens = owners ? devices.allOwners().stream().map(OwnerDevice::token).toList()
                    : devices.allCustomers();
            if (!tokens.isEmpty()) {
                sender.send(tokens.stream().map(build).toList()).ifPresent(results -> forget(results,
                        owners ? devices::forgetOwnerTokens : devices::forgetCustomerTokens));
            }
        } catch (RuntimeException failure) {
            LOG.error("Falha ao avisar todos os aparelhos ({})", what, failure);
        }
    }

    /**
     * Apaga os aparelhos mortos.
     *
     * @param results resultados do envio
     * @param remove  como apagar
     */
    private static void forget(List<PushResult> results, Consumer<List<String>> remove) {
        List<String> dead = results.stream().filter(PushResult::dead).map(PushResult::token).toList();
        if (!dead.isEmpty()) {
            remove.accept(dead);
        }
    }
}
