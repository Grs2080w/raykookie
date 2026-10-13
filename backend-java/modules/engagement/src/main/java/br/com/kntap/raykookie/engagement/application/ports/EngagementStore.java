package br.com.kntap.raykookie.engagement.application.ports;

import br.com.kntap.raykookie.engagement.domain.EngagementBatch;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Onde ficam os eventos e o agregado diário.
 */
public interface EngagementStore {

    /**
     * Grava um lote. Vista e clique do mesmo item na mesma sessão contam uma vez só.
     *
     * @param batch lote já conferido
     * @return quantos eventos entraram (os repetidos ficam de fora)
     */
    int insert(EngagementBatch batch);

    /**
     * Refaz o agregado de um dia a partir dos eventos brutos.
     *
     * @param day dia (no fuso da loja)
     */
    void rollup(LocalDate day);

    /**
     * Apaga eventos brutos antigos (o agregado fica).
     *
     * @param cutoff instante de corte
     * @return quantos foram apagados
     */
    int purgeEventsBefore(Instant cutoff);

    /**
     * Apaga o agregado diário mais antigo que o prazo de consulta (13 meses).
     *
     * @param cutoff primeiro dia que fica
     * @return quantas linhas foram apagadas
     */
    int purgeDailyBefore(LocalDate cutoff);

    /**
     * Sessões e ocorrências de cada evento no período, no total e por detalhe (passo, método, origem…).
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma, ou nulo para todas
     * @return as contagens
     */
    List<Step> steps(LocalDate from, LocalDate to, String platform);

    /**
     * Páginas e telas vistas.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma, ou nulo
     * @return uma linha por rota
     */
    List<PageRow> pages(LocalDate from, LocalDate to, String platform);

    /**
     * Vistas, cliques e adições ao pedido por item do cardápio.
     *
     * @param from     primeiro dia
     * @param to       último dia
     * @param platform plataforma, ou nulo
     * @return uma linha por item e evento
     */
    List<ItemRow> items(LocalDate from, LocalDate to, String platform);

    /**
     * Sessões de entrada por plataforma.
     *
     * @param from primeiro dia
     * @param to   último dia
     * @return uma linha por plataforma
     */
    List<PlatformRow> platforms(LocalDate from, LocalDate to);

    /**
     * Pedidos e receita do período, das tabelas de pedidos (não dos eventos).
     *
     * @param owner dona
     * @param from  primeiro dia
     * @param to    último dia
     * @return os totais
     */
    OrderTotals orders(OwnerId owner, LocalDate from, LocalDate to);

    /**
     * Pedidos e receita por item do cardápio, das tabelas de pedidos.
     *
     * @param owner dona
     * @param from  primeiro dia
     * @param to    último dia
     * @return uma linha por item pedido
     */
    List<SoldItem> soldItems(OwnerId owner, LocalDate from, LocalDate to);

    /**
     * Nome de cada item do cardápio da dona (receitas e combos).
     *
     * @param owner dona
     * @return nome por id de item
     */
    Map<String, String> itemNames(OwnerId owner);

    /**
     * Contagem de um evento e detalhe.
     *
     * @param name     evento
     * @param detail   detalhe ({@code *} = todos)
     * @param events   ocorrências
     * @param sessions sessões distintas
     */
    record Step(String name, String detail, long events, long sessions) {
    }

    /**
     * Uma página ou tela.
     *
     * @param route    rota
     * @param events   visualizações
     * @param sessions sessões distintas
     */
    record PageRow(String route, long events, long sessions) {
    }

    /**
     * Um evento de um item.
     *
     * @param itemId   item
     * @param name     evento
     * @param events   ocorrências
     * @param sessions sessões distintas
     */
    record ItemRow(String itemId, String name, long events, long sessions) {
    }

    /**
     * Sessões de uma plataforma.
     *
     * @param platform plataforma
     * @param sessions sessões
     */
    record PlatformRow(String platform, long sessions) {
    }

    /**
     * Pedidos do período.
     *
     * @param orders  pedidos feitos (sem os cancelados)
     * @param paid    pedidos já pagos
     * @param revenue soma do total dos pedidos pagos
     */
    record OrderTotals(long orders, long paid, BigDecimal revenue) {
    }

    /**
     * Um item pedido.
     *
     * @param itemId  item
     * @param orders  pedidos que o têm
     * @param revenue receita do item
     */
    record SoldItem(String itemId, long orders, BigDecimal revenue) {
    }
}
