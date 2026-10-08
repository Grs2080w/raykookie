package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.DefaultCategories;
import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Categorias da dona (tabela {@code categories}).
 */
public interface Categories {

    /**
     * Categorias com a contagem de usos, por tipo e nome.
     *
     * @param owner dona
     * @param kind  tipo; nulo = todos
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner, String kind);

    /**
     * Cria uma categoria.
     *
     * @param owner    dona
     * @param kind     tipo
     * @param category nome, ícone e cor
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, String kind, SimpleForms.Category category);

    /**
     * Edita uma categoria da dona.
     *
     * @param owner    dona
     * @param id       categoria
     * @param category nome, ícone e cor
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> update(OwnerId owner, long id, SimpleForms.Category category);

    /**
     * Apaga uma categoria da dona (as receitas e os ingredientes ficam sem categoria).
     *
     * @param owner dona
     * @param id    categoria
     */
    void delete(OwnerId owner, long id);

    /**
     * Cria as categorias padrão de uma conta nova.
     *
     * @param owner dona
     * @param seeds as categorias
     */
    void createAll(OwnerId owner, List<DefaultCategories.Seed> seeds);
}
