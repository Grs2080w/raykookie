package br.com.kntap.raykookie.engagement.domain;

import java.util.List;
import java.util.UUID;

/**
 * Um lote de eventos de uma mesma visita.
 *
 * @param sessionId  código aleatório da visita (some quando ela termina; não liga a nenhuma conta)
 * @param platform   {@code site}, {@code android} ou {@code ios}
 * @param appVersion versão do app (vazio no site)
 * @param events     eventos válidos do lote
 */
public record EngagementBatch(UUID sessionId, String platform, String appVersion, List<EngagementEvent> events) {
}
