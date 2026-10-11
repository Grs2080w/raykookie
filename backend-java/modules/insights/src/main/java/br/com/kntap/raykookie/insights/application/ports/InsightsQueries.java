package br.com.kntap.raykookie.insights.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Leituras e gravações do painel e das metas.
 */
public interface InsightsQueries {

    /**
     * Meta do mês ({@code getGoal}), zerada se não houver.
     *
     * @param owner dona
     * @param month mês
     * @return {@code revenue}, {@code orders}, {@code new_customers} e {@code avg_ticket}
     */
    Map<String, Object> goal(OwnerId owner, LocalDate month);

    /**
     * Grava a meta do mês.
     *
     * @param owner        dona
     * @param month        mês
     * @param revenue      faturamento
     * @param orders       pedidos
     * @param newCustomers clientes novos
     * @param avgTicket    ticket médio
     * @return a linha gravada
     */
    Map<String, Object> saveGoal(OwnerId owner, LocalDate month, BigDecimal revenue, long orders, long newCustomers,
            BigDecimal avgTicket);

    /**
     * Realizado do mês, em decimal exato.
     *
     * @param owner dona
     * @param month mês
     * @return faturamento, devolvido, pedidos e clientes novos
     */
    MonthNumbers month(OwnerId owner, LocalDate month);

    /**
     * Unidades vendidas por receita no mês.
     *
     * @param owner dona
     * @param month mês
     * @return receita → unidades
     */
    Map<Long, BigDecimal> unitsSold(OwnerId owner, LocalDate month);

    /**
     * Combos vendidos no mês (pedidos não cancelados): quantidade e valor de cada combo.
     *
     * @param owner dona
     * @param month primeiro dia do mês
     * @return combo → vendas
     */
    Map<Long, ComboSales> combosSold(OwnerId owner, LocalDate month);

    /**
     * Vendas de um combo no mês.
     *
     * @param qty     quantos combos
     * @param revenue valor vendido
     */
    record ComboSales(BigDecimal qty, BigDecimal revenue) {
    }

    /**
     * Devoluções da dona: aguardando decisão, valor em andamento e as que falharam.
     *
     * @param owner dona
     * @return os números
     */
    RefundSummary refunds(OwnerId owner);

    /**
     * Números do mês.
     *
     * @param revenue      faturamento bruto
     * @param refunded     devolvido (o que passa dos itens cancelados)
     * @param orders       pedidos
     * @param newCustomers clientes novos
     */
    record MonthNumbers(BigDecimal revenue, BigDecimal refunded, long orders, long newCustomers) {
    }

    /**
     * Devoluções.
     *
     * @param waiting aguardando decisão
     * @param pending valor em andamento
     * @param failed  falharam
     */
    record RefundSummary(long waiting, BigDecimal pending, long failed) {
    }
}
