package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.SimpleForms;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Map;
import java.util.Optional;

/**
 * Ajustes da dona (tabela {@code settings}).
 */
public interface OwnerSettings {

    /**
     * Os ajustes gravados.
     *
     * @param owner dona
     * @return os ajustes, ou vazio se ainda não houver
     */
    Optional<Values> find(OwnerId owner);

    /**
     * Grava os ajustes (cria ou atualiza).
     *
     * @param owner dona
     * @param form  dados
     * @return a linha gravada no formato do Node
     */
    Map<String, Object> save(OwnerId owner, SimpleForms.Settings form);

    /**
     * Cria os ajustes padrão de uma conta nova.
     *
     * @param owner dona
     */
    void createDefaults(OwnerId owner);

    /**
     * Ajustes usados no cálculo de preço.
     *
     * @param hourlyRate   valor da hora
     * @param monthlyUnits unidades por mês
     * @param minMarginPct margem mínima (%)
     */
    record Values(double hourlyRate, double monthlyUnits, double minMarginPct) {

        /** Ajustes de quem ainda não gravou nenhum (os do Node). */
        public static final Values DEFAULTS = new Values(0, 100, 30);
    }
}
