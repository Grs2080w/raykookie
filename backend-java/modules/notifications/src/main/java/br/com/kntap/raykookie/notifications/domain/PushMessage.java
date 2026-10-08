package br.com.kntap.raykookie.notifications.domain;

import java.util.Map;

/**
 * Uma notificação para um aparelho, no formato do Firebase (como o {@code PushMessage} do Node).
 *
 * @param token     token do aparelho
 * @param title     título
 * @param body      texto
 * @param data      dados que o app lê ao tocar
 * @param channelId canal Android (o som)
 * @param sound     som
 */
public record PushMessage(String token, String title, String body, Map<String, String> data, String channelId,
        String sound) {
}
