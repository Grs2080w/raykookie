package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.kernel.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Quem pode devolver o quê e o estado do pagamento que o cliente vê (porte de {@code backend/shared/refund.ts}), em
 * centavos exatos.
 */
public final class RefundRules {

    /** Depois da entrega, o cliente ainda pode pedir devolução por este prazo. */
    public static final Duration REFUND_WINDOW = Duration.ofHours(48);

    /** O Pix só pode ser devolvido até 90 dias depois; paramos 5 dias antes. */
    public static final Duration PIX_REFUND_LIMIT = Duration.ofDays(85);

    /** Fotos por pedido de devolução. */
    public static final int MAX_REFUND_PHOTOS = 5;

    /** Tamanho máximo de cada foto. */
    public static final int MAX_REFUND_PHOTO_BYTES = 2_000_000;

    /** Tamanho mínimo da descrição do problema. */
    public static final int MIN_REFUND_DESCRIPTION = 20;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private RefundRules() {
    }

    /**
     * O que o cliente pode fazer agora sobre devolução ({@code refundEligibility}).
     *
     * @param i a situação do pedido
     * @return o modo, o máximo e o motivo quando não pode
     */
    public static Eligibility eligibility(Input i) {

        // ── 1. Sem pagamento, já devolvido, com pedido em andamento ou fora do prazo do Pix.
        Money left = Money.ofCents(Math.max(0, i.total().minus(i.refundedTotal()).cents()));
        if (!i.paid()) {
            return none("nao_pago", "O pedido ainda não foi pago.", Money.ZERO);
        }
        if (left.isZero()) {
            return none("devolvido", "Este pedido já foi devolvido.", Money.ZERO);
        }
        if (i.liveRequest()) {
            return none("solicitacao_existente", "Já existe uma solicitação de devolução para este pedido.",
                    Money.ZERO);
        }
        if (i.paidAt() != null && Duration.between(i.paidAt(), i.now()).compareTo(PIX_REFUND_LIMIT) > 0) {
            return none("prazo_pix", "O prazo do Pix para devolução acabou. Fale com a gente.", left);
        }

        // ── 2. Pela situação dos itens.
        List<String> live = i.itemStatuses().stream().filter(s -> s != null && !s.isEmpty() && !"cancelado".equals(s))
                .toList();
        if (live.isEmpty()) {
            return none("cancelado", "O pedido foi cancelado.", left);
        }
        if (live.stream().allMatch("novo"::equals)) {
            return new Eligibility("automatico", left, false, null, "", "");
        }
        return delivered(i, live, left);
    }

    /**
     * Pedido em produção ou entregue: solicitação, com fotos e prazo quando entregue.
     *
     * @param i    a situação
     * @param live itens não cancelados
     * @param left quanto ainda pode voltar
     * @return a elegibilidade
     */
    private static Eligibility delivered(Input i, List<String> live, Money left) {
        boolean delivered = live.stream().allMatch("entregue"::equals);
        if (delivered && i.deliveredAt() != null) {
            Instant deadline = i.deliveredAt().plus(REFUND_WINDOW);
            if (i.now().isAfter(deadline)) {
                return none("fora_prazo", "O prazo para pedir devolução já acabou.", left);
            }
            return new Eligibility("solicitacao", left, true, deadline, "", "");
        }
        return new Eligibility("solicitacao", left, delivered, null, "", "");
    }

    /**
     * Sem devolução possível.
     *
     * @param code   código do motivo
     * @param reason motivo
     * @param max    valor que ainda poderia voltar
     * @return a elegibilidade
     */
    private static Eligibility none(String code, String reason, Money max) {
        return new Eligibility("nenhum", max, false, null, reason, code);
    }

    /**
     * Estado do pagamento que o cliente vê ({@code paymentState}).
     *
     * @param paid          se foi pago
     * @param cancelled     se foi cancelado
     * @param pixExpired    se o Pix venceu
     * @param total         total
     * @param refundedTotal quanto já voltou
     * @return {@code pendente}, {@code expirado}, {@code cancelado}, {@code pago}, {@code devolucao_parcial} ou
     *         {@code devolvido}
     */
    public static String paymentState(boolean paid, boolean cancelled, boolean pixExpired, Money total,
            Money refundedTotal) {
        if (paid) {
            if (!refundedTotal.isLessThan(total) && total.cents() > 0) {
                return "devolvido";
            }
            return refundedTotal.cents() > 0 ? "devolucao_parcial" : "pago";
        }
        if (cancelled) {
            return "cancelado";
        }
        return pixExpired ? "expirado" : "pendente";
    }

    /**
     * O que o cliente lê em cada estado da devolução ({@code refundCustomerLabel}); falha nunca aparece para ele.
     *
     * @param status estado
     * @return o rótulo
     */
    public static String customerLabel(String status) {
        return switch (Objects.requireNonNull(status)) {
            case "solicitado" -> "Em análise";
            case "aprovado", "processando", "falhou" -> "Devolução aprovada, em processamento";
            case "concluido" -> "Devolvido";
            case "negado" -> "Devolução negada";
            case "desistiu" -> "Solicitação cancelada";
            default -> status;
        };
    }

    /**
     * Situação do pedido.
     *
     * @param paid          se foi pago
     * @param paidAt        quando
     * @param total         total
     * @param refundedTotal devolvido mais o que está em andamento
     * @param itemStatuses  situação de cada item
     * @param deliveredAt   quando o último item vivo foi entregue
     * @param liveRequest   se há solicitação do cliente em aberto
     * @param now           agora
     */
    public record Input(boolean paid, Instant paidAt, Money total, Money refundedTotal, List<String> itemStatuses,
            Instant deliveredAt, boolean liveRequest, Instant now) {
    }

    /**
     * O que o cliente pode fazer.
     *
     * @param mode           {@code automatico}, {@code solicitacao} ou {@code nenhum}
     * @param maxAmount      quanto pode voltar
     * @param photosRequired se fotos são exigidas
     * @param deadline       prazo (nulo = sem prazo)
     * @param whyNot         por que não pode (vazio = pode)
     * @param whyCode        código do motivo
     */
    public record Eligibility(String mode, Money maxAmount, boolean photosRequired, Instant deadline, String whyNot,
            String whyCode) {
    }
}
