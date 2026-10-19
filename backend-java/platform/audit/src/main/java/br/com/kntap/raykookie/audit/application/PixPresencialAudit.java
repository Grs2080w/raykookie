package br.com.kntap.raykookie.audit.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Histórico da Venda Presencial. O Pix estático é gerado no aparelho, sem servidor, então o app avisa depois, quando há
 * internet (fila offline): cada QR gerado e cada mudança da chave. É informação do aparelho, por isso cada registro
 * leva {@code reported_by_device}. A chave Pix em si nunca é enviada nem gravada.
 */
@Service
public class PixPresencialAudit {

    /** Eventos aceitos por chamada (o app envia a fila em lotes). */
    public static final int MAX_EVENTS = 50;

    /** Maior valor aceito para um QR (o mesmo teto do BR Code). */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999.99");

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /**
     * Cria o serviço.
     *
     * @param audit histórico de auditoria
     */
    public PixPresencialAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * Grava os eventos informados pelo aparelho, todos ou nenhum.
     *
     * @param owner  dona
     * @param events eventos: {@code kind} ({@code qr_gerado}, {@code chave_alterada}, {@code chave_removida}),
     *               {@code title}, {@code amount} (QR), {@code key_kind} (chave) e {@code at} (hora do aparelho)
     * @return quantos foram gravados
     * @throws InvalidInputException se a lista ou algum evento for inválido
     */
    @Transactional
    public int record(OwnerId owner, List<?> events) {

        // ── 1. Tamanho do lote.
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new InvalidInputException("Envie de 1 a " + MAX_EVENTS + " eventos.");
        }

        // ── 2. Cada evento vira uma linha do histórico, na mesma transação.
        for (Object raw : events) {
            if (!(raw instanceof Map<?, ?> event)) {
                throw new InvalidInputException("Evento inválido.");
            }
            audit.record(owner, entry(event));
        }
        return events.size();
    }

    /**
     * Valida um evento e o transforma em linha do histórico.
     *
     * @param event evento do aparelho
     * @return a linha
     */
    private static Entry entry(Map<?, ?> event) {
        String kind = text(event.get("kind"), 20);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reported_by_device", true);
        after.put("device_time", text(event.get("at"), 40));
        return switch (kind) {
            case "qr_gerado" -> qr(event, after);
            case "chave_alterada", "chave_removida" -> key(kind, event, after);
            default -> throw new InvalidInputException("Tipo de evento inválido.");
        };
    }

    /**
     * QR Code gerado no aparelho.
     *
     * @param event evento
     * @param after valores comuns
     * @return a linha
     */
    private static Entry qr(Map<?, ?> event, Map<String, Object> after) {
        String title = text(event.get("title"), 120);
        BigDecimal amount = amount(event.get("amount"));
        after.put("title", title);
        after.put("amount", amount);
        return new Entry("pix_presencial", "gerou", "pix_qr", title, null, after,
                "QR Pix estático gerado no aparelho: " + title + " por R$ " + AuditValues.num(amount),
                "Venda Presencial");
    }

    /**
     * Chave Pix alterada ou removida no aparelho (só o tipo, nunca a chave).
     *
     * @param kind  {@code chave_alterada} ou {@code chave_removida}
     * @param event evento
     * @param after valores comuns
     * @return a linha
     */
    private static Entry key(String kind, Map<?, ?> event, Map<String, Object> after) {
        String keyKind = text(event.get("key_kind"), 20);
        after.put("key_kind", keyKind);
        boolean removed = "chave_removida".equals(kind);
        return new Entry("pix_presencial", removed ? "excluiu" : "editou", "pix_key", "chave", null, after,
                "Chave Pix da venda presencial " + (removed ? "removida" : "alterada") + " no aparelho"
                        + (keyKind.isEmpty() ? "" : " (tipo " + keyKind + ")"), "Venda Presencial");
    }

    /**
     * Valor do QR: positivo, até o teto, com centavos.
     *
     * @param value valor do JSON
     * @return o valor em reais
     */
    private static BigDecimal amount(Object value) {
        try {
            BigDecimal amount = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                throw new InvalidInputException("Valor do QR inválido.");
            }
            return amount;
        } catch (NumberFormatException notANumber) {
            throw new InvalidInputException("Valor do QR inválido.");
        }
    }

    /**
     * Texto curto, sem quebras de linha.
     *
     * @param value valor do JSON
     * @param max   tamanho máximo
     * @return o texto limpo ({@code ""} se não houver)
     */
    private static String text(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
