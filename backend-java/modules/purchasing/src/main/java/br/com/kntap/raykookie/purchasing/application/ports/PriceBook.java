package br.com.kntap.raykookie.purchasing.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cotações e produtos ligados (tabelas {@code price_quotes} e {@code supplier_products}) e o preço dos
 * ingredientes que elas alimentam.
 */
public interface PriceBook {

    /**
     * Ingrediente da dona.
     *
     * @param owner dona
     * @param id    ingrediente
     * @return id, nome, unidade e preço da embalagem, ou vazio se não for da dona
     */
    Optional<Ingredient> ingredient(OwnerId owner, long id);

    /**
     * Liga (ou atualiza) um produto do site ao ingrediente.
     *
     * @param product o produto
     * @return o id do produto
     */
    long linkProduct(LinkedProduct product);

    /**
     * Grava uma cotação.
     *
     * @param quote a cotação
     * @return o id (bigint, em texto como o Node devolve)
     */
    String addQuote(Quote quote);

    /**
     * Última cotação de cada fornecedor para o ingrediente.
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @return as linhas no formato do Node (ordem do banco)
     */
    List<Map<String, Object>> latestBySupplier(OwnerId owner, long ingredient);

    /**
     * Histórico de preços (60 mais novos).
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> history(OwnerId owner, long ingredient);

    /**
     * O ingrediente como {@code /api/ingredients/{id}/prices} o devolve.
     *
     * @param owner dona
     * @param id    ingrediente
     * @return a linha, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> ingredientRow(OwnerId owner, long id);

    /**
     * Uma cotação da dona para o ingrediente.
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @param quoteId    cotação
     * @return a cotação, ou vazio
     */
    Optional<Quote> quote(OwnerId owner, long ingredient, long quoteId);

    /**
     * Troca o preço da embalagem do ingrediente.
     *
     * @param ingredient ingrediente
     * @param cost       custo
     * @param size       tamanho
     */
    void setIngredientPrice(long ingredient, double cost, double size);

    /**
     * Produtos ligados a ingredientes em sites Tray, para a atualização diária.
     *
     * @return os produtos, por fornecedor
     */
    List<Linked> linkedForRefresh();

    /**
     * Atualiza a disponibilidade (e a data em que o produto foi visto).
     *
     * @param productId produto
     * @param available se está disponível
     */
    void markSeen(long productId, boolean available);

    /**
     * Atualiza o preço do produto.
     *
     * @param productId produto
     * @param price     preço novo
     */
    void updateProductPrice(long productId, double price);

    /**
     * Ingrediente.
     *
     * @param id   id
     * @param name nome
     * @param unit unidade
     * @param cost custo da embalagem
     * @param size tamanho da embalagem
     */
    record Ingredient(long id, String name, String unit, double cost, double size) {
    }

    /**
     * Produto de site ligado a um ingrediente.
     *
     * @param supplierId   fornecedor
     * @param ingredientId ingrediente
     * @param url          link do produto
     * @param name         nome
     * @param size         tamanho da embalagem
     * @param unit         unidade
     * @param price        preço
     */
    record LinkedProduct(long supplierId, long ingredientId, String url, String name, double size, String unit,
            double price) {
    }

    /**
     * Cotação.
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @param supplierId fornecedor (nulo = nenhum)
     * @param productId  produto ligado (nulo = nenhum)
     * @param price      preço
     * @param size       tamanho da embalagem
     * @param unit       unidade
     * @param source     origem ({@code scraper}, {@code manual}…)
     */
    record Quote(OwnerId owner, long ingredient, Long supplierId, Long productId, double price, double size,
            String unit, String source) {
    }

    /**
     * Produto a atualizar.
     *
     * @param id           produto
     * @param supplierId   fornecedor
     * @param ingredientId ingrediente
     * @param url          link
     * @param name         nome (a busca usa o nome)
     * @param size         tamanho
     * @param unit         unidade
     * @param price        preço gravado
     * @param base         endereço da loja
     * @param owner        dona
     */
    record Linked(long id, long supplierId, long ingredientId, String url, String name, double size, String unit,
            double price, String base, OwnerId owner) {
    }
}
