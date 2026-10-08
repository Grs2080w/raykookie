package br.com.kntap.raykookie.notifications.application.ports;

import br.com.kntap.raykookie.notifications.domain.PushMessage;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saída: o envio das notificações (Firebase em produção).
 */
public interface PushSender {

    /**
     * Manda as mensagens.
     *
     * @param messages mensagens (uma por aparelho)
     * @return o resultado de cada uma, na mesma ordem; vazio se o push está desligado ou indisponível agora
     */
    Optional<List<PushResult>> send(List<PushMessage> messages);

    /**
     * Resultado de uma mensagem.
     *
     * @param token token do aparelho
     * @param ok    entregue ao Firebase
     * @param dead  o token não existe mais (o aparelho é apagado)
     */
    record PushResult(String token, boolean ok, boolean dead) {
    }
}
