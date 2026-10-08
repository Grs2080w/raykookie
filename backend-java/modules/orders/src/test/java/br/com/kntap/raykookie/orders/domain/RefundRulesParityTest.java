package br.com.kntap.raykookie.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Paridade com o TypeScript ({@code rules-vectors/refund-rules.json}, gerado por
 * {@code infra/rules-vectors/gen-refund-rules.mjs}): elegibilidade de devolução, estado do pagamento, situação geral,
 * rótulos e as listas de motivos.
 */
class RefundRulesParityTest {

    /** Vetores. */
    private static JsonNode vectors;

    /**
     * Lê os vetores.
     *
     * @throws IOException se o arquivo não abrir
     */
    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = RefundRulesParityTest.class.getResourceAsStream("/rules-vectors/refund-rules.json")) {
            vectors = new ObjectMapper().readTree(in);
        }
    }

    /**
     * Valor em dinheiro de um número do JSON.
     *
     * @param node número
     * @return o valor
     */
    private static Money money(JsonNode node) {
        return Money.fromDecimal(new BigDecimal(node.asString()));
    }

    /**
     * Instante ou nulo.
     *
     * @param node texto ISO ou nulo
     * @return o instante
     */
    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.asString());
    }

    /**
     * Lista de textos (nulos preservados).
     *
     * @param node array
     * @return a lista
     */
    private static List<String> strings(JsonNode node) {
        List<String> list = new ArrayList<>();
        node.forEach(item -> list.add(item.isNull() ? null : item.asString()));
        return list;
    }

    /**
     * Elegibilidade: os 400 vetores.
     */
    @Test
    @DisplayName("elegibilidade de devolução igual ao TypeScript em 400 situações")
    void eligibility() {
        int checked = 0;
        for (JsonNode row : vectors.get("eligibility")) {

            // ── 1. Entrada do vetor.
            JsonNode in = row.get("input");
            RefundRules.Eligibility e = RefundRules.eligibility(new RefundRules.Input(in.get("paid").asBoolean(),
                    instant(in.get("paidAt")), money(in.get("total")), money(in.get("refundedTotal")),
                    strings(in.get("itemStatuses")), instant(in.get("deliveredAt")), in.get("liveRequest").asBoolean(),
                    instant(in.get("now"))));

            // ── 2. Cada campo da saída.
            JsonNode out = row.get("output");
            assertThat(e.mode()).as(in.toString()).isEqualTo(out.get("mode").asString());
            assertThat(e.maxAmount()).as(in.toString()).isEqualTo(money(out.get("max_amount")));
            assertThat(e.photosRequired()).as(in.toString()).isEqualTo(out.get("photos_required").asBoolean());
            assertThat(e.deadline()).as(in.toString()).isEqualTo(instant(out.get("deadline")));
            assertThat(e.whyNot()).as(in.toString()).isEqualTo(out.get("why_not").asString());
            assertThat(e.whyCode()).as(in.toString()).isEqualTo(out.get("why_code").asString());
            checked++;
        }
        assertThat(checked).isEqualTo(400);
    }

    /**
     * Estado do pagamento, situação geral e rótulos.
     */
    @Test
    @DisplayName("estado do pagamento, situação geral e rótulos iguais ao TypeScript")
    void statesAndLabels() {

        // ── 1. Estado do pagamento.
        for (JsonNode row : vectors.get("paymentStates")) {
            JsonNode in = row.get("input");
            assertThat(RefundRules.paymentState(in.get("paid").asBoolean(), in.get("cancelled").asBoolean(),
                    in.get("pixExpired").asBoolean(), money(in.get("total")), money(in.get("refundedTotal"))))
                    .as(in.toString()).isEqualTo(row.get("output").asString());
        }

        // ── 2. Situação geral e rótulos.
        for (JsonNode row : vectors.get("overall")) {
            assertThat(OrderStatus.overall(strings(row.get("input")))).as(row.toString())
                    .isEqualTo(row.get("output").asString());
        }
        for (JsonNode row : vectors.get("labels")) {
            assertThat(RefundRules.customerLabel(row.get("input").asString())).isEqualTo(row.get("output").asString());
        }
    }

    /**
     * Listas de motivos.
     */
    @Test
    @DisplayName("motivos de cancelamento, devolução, recusa e sistema iguais ao TypeScript")
    void reasons() {

        // ── 1. Listas simples.
        JsonNode reasons = vectors.get("reasons");
        assertSame(Reasons.CANCEL, reasons.get("CANCEL_REASONS"));
        assertSame(Reasons.STORE_CANCEL, reasons.get("STORE_CANCEL_REASONS"));
        assertSame(Reasons.DENY, reasons.get("DENY_REASONS"));
        assertSame(Reasons.SYSTEM, reasons.get("SYSTEM_REASONS"));

        // ── 2. Motivos de devolução, com a exigência de fotos.
        JsonNode refund = reasons.get("REFUND_REASONS");
        assertThat(Reasons.REFUND).hasSize(refund.size());
        for (int i = 0; i < refund.size(); i++) {
            assertThat(Reasons.REFUND.get(i).id()).isEqualTo(refund.get(i).get("id").asString());
            assertThat(Reasons.REFUND.get(i).label()).isEqualTo(refund.get(i).get("label").asString());
            assertThat(Reasons.REFUND.get(i).photos()).isEqualTo(refund.get(i).get("photos").asString());
        }
    }

    /**
     * Compara uma lista de motivos.
     *
     * @param list     lista do Java
     * @param expected lista do TypeScript
     */
    private static void assertSame(List<Reasons.Reason> list, JsonNode expected) {
        assertThat(list).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(list.get(i)).isEqualTo(new Reasons.Reason(expected.get(i).get("id").asString(),
                    expected.get(i).get("label").asString()));
        }
    }
}
