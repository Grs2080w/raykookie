package br.com.kntap.raykookie.insights.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.insights.application.ports.InsightsQueries;
import br.com.kntap.raykookie.insights.domain.Months;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.api.OrdersApi;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.security.ShopOwner;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Painel da dona e metas do mês ({@code backend/lib/dashboard.ts} e a rota {@code goals}).
 */
@Service
public class InsightsUseCases {

    /** Leituras. */
    private final InsightsQueries queries;

    /** Receitas e ingredientes. */
    private final CatalogApi catalog;

    /** Pedidos. */
    private final OrdersApi orders;

    /** A dona (nome de usuário). */
    private final ShopOwner shopOwner;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param queries   leituras
     * @param catalog   receitas e ingredientes
     * @param orders    pedidos
     * @param shopOwner a dona
     * @param clock     relógio
     */
    public InsightsUseCases(InsightsQueries queries, CatalogApi catalog, OrdersApi orders, ShopOwner shopOwner,
            Clock clock) {
        this.queries = queries;
        this.catalog = catalog;
        this.orders = orders;
        this.shopOwner = shopOwner;
        this.clock = clock;
    }

    /**
     * Resumo da tela inicial.
     *
     * @param owner dona
     * @return o painel no formato do Node
     */
    public Map<String, Object> dashboard(OwnerId owner) {

        // ── 1. Dados do mês, estoque, pedidos e receitas.
        LocalDate month = Months.current(clock);
        List<PricedRecipe> recipes = catalog.pricedRecipes(owner);
        List<Map<String, Object>> ingredients = catalog.ingredients(owner);
        List<Map<String, Object>> list = orders.ownerOrders(owner);
        InsightsQueries.RefundSummary rf = queries.refunds(owner);

        // ── 2. Os recortes do painel.
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("refundsWaiting", rf.waiting());
        d.put("refundsPending", NodeRows.number(rf.pending()));
        d.put("refundsFailed", rf.failed());
        d.put("username", shopOwner.username());
        d.put("month", month.toString());
        d.put("ingredientCount", ingredients.size());
        d.put("goal", queries.goal(owner, month));
        d.put("stats", stats(owner, month, recipes));
        d.put("receivable", NodeRows.number(receivable(list)));
        d.put("empty", ingredients.isEmpty() && recipes.isEmpty());
        d.put("low", ingredients.stream().filter(i -> !"ok".equals(Months.stockStatus(num(i.get("stock_qty")),
                num(i.get("min_stock"))))).limit(6).toList());
        d.put("upcoming", list.stream().filter(o -> !List.of("entregue", "cancelado").contains(o.get("status")))
                .sorted(Comparator.comparing(o -> Objects.toString(o.get("delivery_date"), "9999"))).limit(5).toList());
        d.put("best", recipes.stream().filter(r -> r.pricing().rounded() > 0)
                .sorted(Comparator.comparingDouble((PricedRecipe r) -> r.pricing().realMarginPct()).reversed())
                .limit(5).map(PricedRecipe::json).toList());
        return d;
    }

    /**
     * A receber: pedidos não cancelados e não pagos, em decimal exato.
     *
     * @param list pedidos
     * @return o total
     */
    private static BigDecimal receivable(List<Map<String, Object>> list) {
        return list.stream().filter(o -> !"cancelado".equals(o.get("status")) && !"pago".equals(o.get("payment")))
                .map(o -> decimal(o.get("qty")).multiply(decimal(o.get("unit_price"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Realizado do mês ({@code getMonthStats}): faturamento sem o devolvido, lucro pelas receitas, ticket médio.
     *
     * @param owner   dona
     * @param month   mês
     * @param recipes receitas com preço
     * @return {@code revenue}, {@code profit}, {@code orders}, {@code avg_ticket} e {@code new_customers}
     */
    Map<String, Object> stats(OwnerId owner, LocalDate month, List<PricedRecipe> recipes) {

        // ── 1. Faturamento sem o devolvido.
        InsightsQueries.MonthNumbers n = queries.month(owner, month);
        BigDecimal revenue = n.revenue().subtract(n.refunded()).max(BigDecimal.ZERO);

        // ── 2. Lucro: unidades × lucro unitário da receita, menos o devolvido.
        Map<Long, Double> profitPerUnit = new LinkedHashMap<>();
        recipes.forEach(r -> profitPerUnit.put(r.id(), r.pricing().profitPerUnit()));
        double profit = queries.unitsSold(owner, month).entrySet().stream()
                .mapToDouble(e -> e.getValue().doubleValue() * profitPerUnit.getOrDefault(e.getKey(), 0.0)).sum();

        // ── 3. Os números.
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("revenue", NodeRows.number(revenue));
        stats.put("profit", NodeRows.number(profit - n.refunded().doubleValue()));
        stats.put("orders", n.orders());
        stats.put("avg_ticket", NodeRows.number(n.orders() > 0 ? revenue.doubleValue() / n.orders() : 0));
        stats.put("new_customers", n.newCustomers());
        return stats;
    }

    /**
     * Meta e realizado de um mês ({@code ?month=AAAA-MM-01}; padrão: mês atual).
     *
     * @param owner dona
     * @param month mês pedido
     * @return {@code month}, {@code goal} e {@code stats}
     */
    public Map<String, Object> goals(OwnerId owner, String month) {
        LocalDate m = Months.parse(month).orElseGet(() -> Months.current(clock));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", m.toString());
        result.put("goal", queries.goal(owner, m));
        result.put("stats", stats(owner, m, catalog.pricedRecipes(owner)));
        return result;
    }

    /**
     * Grava a meta do mês (valores negativos são recusados).
     *
     * @param owner dona
     * @param body  {@code month}, {@code revenue}, {@code orders}, {@code new_customers} e {@code avg_ticket}
     * @return a linha gravada
     */
    public Map<String, Object> saveGoal(OwnerId owner, Map<String, Object> body) {
        LocalDate month = Months.parse(NodeInput.text(body.get("month")))
                .orElseThrow(() -> new InvalidInputException("Mês inválido."));
        return queries.saveGoal(owner, month, NodeInput.decimal(body.get("revenue"), 0),
                Math.round(NodeInput.number(body.get("orders"))),
                Math.round(NodeInput.number(body.get("new_customers"))),
                NodeInput.decimal(body.get("avg_ticket"), 0));
    }

    /**
     * Número de uma linha.
     *
     * @param value valor
     * @return o número (nulo = 0)
     */
    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * Decimal exato de uma linha.
     *
     * @param value valor
     * @return o decimal (nulo = 0)
     */
    private static BigDecimal decimal(Object value) {
        return value instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    }
}
