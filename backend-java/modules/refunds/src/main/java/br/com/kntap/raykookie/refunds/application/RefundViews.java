package br.com.kntap.raykookie.refunds.application;

import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.orders.api.rules.Reasons;
import br.com.kntap.raykookie.refunds.application.ports.RefundQueries;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Telas de devolução: a lista e o detalhe da dona, e as fotos (da dona e do próprio cliente).
 */
@Service
public class RefundViews {

    /** Grupos da lista ({@code ?status=}). */
    static final Map<String, List<String>> GROUPS = groups();

    /** Leituras. */
    private final RefundQueries queries;

    /** Histórico do pedido. */
    private final OrdersApi orders;

    /**
     * Cria o serviço.
     *
     * @param queries leituras
     * @param orders  histórico
     */
    public RefundViews(RefundQueries queries, OrdersApi orders) {
        this.queries = queries;
        this.orders = orders;
    }

    /**
     * Grupos na ordem do Node.
     *
     * @return grupo → situações
     */
    private static Map<String, List<String>> groups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("aguardando", List.of("solicitado"));
        groups.put("andamento", List.of("aprovado", "processando", "falhou"));
        groups.put("concluidas", List.of("concluido"));
        groups.put("negadas", List.of("negado", "desistiu"));
        return groups;
    }

    /**
     * Lista da dona, com as contagens por grupo e o valor em andamento.
     *
     * @param owner dona
     * @param group grupo (desconhecido = aguardando)
     * @return {@code list}, {@code counts} e {@code pending_amount}
     */
    public Map<String, Object> list(OwnerId owner, String group) {

        // ── 1. Situações do grupo e contagens.
        List<String> statuses = GROUPS.getOrDefault(group == null ? "aguardando" : group, GROUPS.get("aguardando"));
        Map<String, Integer> byStatus = queries.counts(owner);
        Map<String, Object> counts = new LinkedHashMap<>();
        GROUPS.forEach((name, list) -> counts.put(name, list.stream().mapToInt(s -> byStatus.getOrDefault(s, 0))
                .sum()));

        // ── 2. A resposta do Node.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", queries.list(owner, statuses));
        result.put("counts", counts);
        result.put("pending_amount", queries.pendingAmount(owner));
        return result;
    }

    /**
     * Uma devolução com o pedido, as fotos e o histórico.
     *
     * @param refundId devolução
     * @param owner    dona
     * @return o detalhe
     */
    public Map<String, Object> detail(long refundId, OwnerId owner) {

        // ── 1. A devolução com o pedido.
        Map<String, Object> r = new LinkedHashMap<>(queries.detail(refundId, owner)
                .orElseThrow(() -> new NotFoundException("Solicitação não encontrada.")));
        Object decision = r.get("decision_code");

        // ── 2. Rótulos, fotos e histórico.
        r.put("reason", Reasons.label((String) r.get("reason_code")));
        r.put("decision_reason", NodeInput.truthy(decision) ? Reasons.label((String) decision) : "");
        r.put("photos", queries.photoIndexes(refundId).stream().map(n -> Map.of("n", n, "url",
                "/api/devolucoes/" + refundId + "/fotos/" + n)).toList());
        r.put("events", orders.events(((Number) r.get("order_id")).longValue()));
        return r;
    }

    /**
     * Foto de uma devolução da dona.
     *
     * @param refundId devolução
     * @param owner    dona
     * @param n        índice
     * @return a foto
     */
    public Optional<RefundQueries.Photo> ownerPhoto(long refundId, OwnerId owner, int n) {
        return queries.ownerPhoto(refundId, owner, n);
    }

    /**
     * Foto do próprio cliente.
     *
     * @param refundId devolução
     * @param customer cliente
     * @param n        índice
     * @return a foto
     */
    public Optional<RefundQueries.Photo> customerPhoto(long refundId, CustomerId customer, int n) {
        return queries.customerPhoto(refundId, customer, n);
    }
}
