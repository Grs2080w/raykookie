package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gastos fixos e variáveis do mês (tabela {@code fixed_costs}).
 */
public interface FixedCosts {

    /**
     * Gastos por nome.
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Cria um gasto.
     *
     * @param owner dona
     * @param form  dados
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, SimpleForms.FixedCost form);

    /**
     * Edita um gasto da dona.
     *
     * @param owner dona
     * @param id    gasto
     * @param form  dados
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> update(OwnerId owner, long id, SimpleForms.FixedCost form);

    /**
     * Apaga um gasto da dona.
     *
     * @param owner dona
     * @param id    gasto
     */
    void delete(OwnerId owner, long id);

    /**
     * Soma dos gastos do mês ({@code getFixedTotal}).
     *
     * @param owner dona
     * @return a soma
     */
    double total(OwnerId owner);
}
