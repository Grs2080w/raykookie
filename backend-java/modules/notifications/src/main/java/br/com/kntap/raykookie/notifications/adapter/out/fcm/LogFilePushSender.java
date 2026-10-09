package br.com.kntap.raykookie.notifications.adapter.out.fcm;

import br.com.kntap.raykookie.notifications.application.ports.PushSender;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Para testes de ponta a ponta ({@code PUSH_LOG_PATH}, como no Node): cada notificação vira uma linha JSON
 * num arquivo, em vez de ir para o Firebase.
 */
public class LogFilePushSender implements PushSender {

    /** Arquivo. */
    private final Path file;

    /** Relógio. */
    private final Clock clock;

    /** JSON. */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Cria o envio para arquivo.
     *
     * @param file  arquivo
     * @param clock relógio
     */
    public LogFilePushSender(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<List<PushResult>> send(List<PushMessage> messages) {
        String lines = messages.stream().map(this::line).collect(Collectors.joining("\n", "", "\n"));
        try {
            Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return Optional.of(messages.stream().map(m -> new PushResult(m.token(), true, false)).toList());
    }

    /**
     * Uma linha no formato do Node ({@code {at, token, notification, data, android}}).
     *
     * @param message mensagem
     * @return a linha JSON
     */
    private String line(PushMessage message) {

        // ── 1. Quando, para quem e o que.
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.instant().toString());
        line.put("token", message.token());
        line.put("notification", Map.of("title", message.title(), "body", message.body()));
        line.put("data", message.data());

        // ── 2. Canal e som do Android.
        line.put("android", Map.of("priority", "high",
                "notification", Map.of("channelId", message.channelId(), "sound", message.sound())));
        return json.writeValueAsString(line);
    }
}
