package br.com.kntap.raykookie.catalog.application.ports;

import br.com.kntap.raykookie.catalog.domain.ComboForm;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Optional;

/**
 * Combos do cardápio (tabelas {@code combos} e {@code combo_items}) e o desconto sugerido de combo da dona.
 */
public interface Combos {

    /**
     * Combos da dona, por nome.
     *
     * @param owner dona
     * @return os combos com os itens
     */
    List<Combo> list(OwnerId owner);

    /**
     * Cria um combo.
     *
     * @param owner dona
     * @param form  dados
     * @return o id
     */
    long create(OwnerId owner, ComboForm form);

    /**
     * Edita um combo.
     *
     * @param owner dona
     * @param id    combo
     * @param form  dados
     * @return verdadeiro se o combo era da dona
     */
    boolean update(OwnerId owner, long id, ComboForm form);

    /**
     * Apaga um combo.
     *
     * @param owner dona
     * @param id    combo
     */
    void delete(OwnerId owner, long id);

    /**
     * Desconto sugerido de combo (%).
     *
     * @param owner dona
     * @return o desconto (10 se a dona nunca mudou)
     */
    double discountPct(OwnerId owner);

    /**
     * Troca o desconto sugerido de combo.
     *
     * @param owner dona
     * @param pct   desconto (%)
     */
    void setDiscountPct(OwnerId owner, double pct);

    /**
     * Um combo gravado.
     *
     * @param id         id
     * @param name       nome
     * @param price      preço escolhido (nulo = o sugerido)
     * @param showOnMenu se aparece no cardápio
     * @param items      produtos e quantidades
     */
    record Combo(long id, String name, Double price, boolean showOnMenu, List<ComboForm.Item> items) {

        /**
         * Busca o combo pelo id.
         *
         * @param combos combos
         * @param id     id
         * @return o combo
         */
        public static Optional<Combo> find(List<Combo> combos, long id) {
            return combos.stream().filter(combo -> combo.id() == id).findFirst();
        }
    }
}
