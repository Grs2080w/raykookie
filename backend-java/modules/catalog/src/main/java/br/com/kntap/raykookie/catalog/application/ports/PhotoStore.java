package br.com.kntap.raykookie.catalog.application.ports;

import java.util.Optional;

/**
 * Onde as fotos das receitas ficam (hoje, a tabela {@code recipe_photos}; G27).
 */
public interface PhotoStore {

    /**
     * Guarda ou troca a foto de uma receita.
     *
     * @param recipeId    receita
     * @param contentType tipo conferido pelo conteúdo
     * @param data        bytes
     */
    void save(long recipeId, String contentType, byte[] data);

    /**
     * Foto de uma receita, se a receita for desta conta.
     *
     * @param recipeId receita
     * @param ownerId  dono da receita
     * @return a foto
     */
    Optional<Photo> load(long recipeId, long ownerId);

    /**
     * Apaga a foto de uma receita da conta.
     *
     * @param recipeId receita
     * @param ownerId  dono da receita
     */
    void delete(long recipeId, long ownerId);

    /**
     * Uma foto.
     *
     * @param contentType tipo
     * @param data        bytes
     */
    record Photo(String contentType, byte[] data) {
    }
}
