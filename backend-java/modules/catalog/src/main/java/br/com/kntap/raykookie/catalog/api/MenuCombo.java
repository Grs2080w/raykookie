package br.com.kntap.raykookie.catalog.api;

import java.util.List;

/**
 * Combo pronto para o cardápio: só os que podem ser vendidos (ligados, com todos os produtos no cardápio e preço).
 *
 * @param id           id
 * @param name         nome
 * @param price        preço do combo (o escolhido ou o sugerido)
 * @param fullPrice    soma dos produtos avulsos
 * @param contents     resumo ("4 Cookie + 2 Brownie")
 * @param parts        produtos e quantidades
 * @param photoRecipe  produto cuja foto representa o combo (nulo = nenhum tem foto)
 * @param photoVersion versão da foto desse produto
 * @param tags         selos somados dos produtos
 */
public record MenuCombo(long id, String name, double price, double fullPrice, String contents, List<Part> parts,
        Long photoRecipe, String photoVersion, List<String> tags) {

    /**
     * Um produto no combo.
     *
     * @param recipeId produto
     * @param name     nome
     * @param qty      quantidade
     */
    public record Part(long recipeId, String name, int qty) {
    }
}
