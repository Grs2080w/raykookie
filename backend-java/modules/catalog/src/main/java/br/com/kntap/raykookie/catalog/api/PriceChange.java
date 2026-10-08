package br.com.kntap.raykookie.catalog.api;

import br.com.kntap.raykookie.kernel.ids.OwnerId;

/**
 * Mudança no preço da embalagem de um ingrediente.
 *
 * @param owner        dona
 * @param ingredientId ingrediente
 * @param before       custo e tamanho da embalagem antes
 * @param after        custo e tamanho depois
 * @param source       origem: {@code edicao}, {@code nota}, {@code scraper} ou {@code manual}
 * @param supplierId   fornecedor da cotação (nulo = nenhum)
 */
public record PriceChange(OwnerId owner, long ingredientId, PriceState before, PriceState after, String source,
        Long supplierId) {

    /**
     * Custo e tamanho de uma embalagem.
     *
     * @param cost custo da embalagem
     * @param size tamanho (g, ml ou un)
     */
    public record PriceState(double cost, double size) {
    }
}
