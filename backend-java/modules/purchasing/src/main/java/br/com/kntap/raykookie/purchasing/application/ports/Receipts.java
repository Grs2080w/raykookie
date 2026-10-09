package br.com.kntap.raykookie.purchasing.application.ports;

import br.com.kntap.raykookie.catalog.api.PriceChange.PriceState;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Notas fiscais lançadas, apelidos de itens e o estoque que elas alimentam (tabelas {@code receipt_imports},
 * {@code receipt_aliases} e {@code ingredients}).
 */
public interface Receipts {

    /**
     * Ingredientes da dona para o casamento, por nome.
     *
     * @param owner dona
     * @return os ingredientes
     */
    List<NfceMatching.Ingredient> ingredients(OwnerId owner);

    /**
     * Apelidos já confirmados.
     *
     * @param owner dona
     * @return palavras do item → ingrediente
     */
    Map<String, Long> aliases(OwnerId owner);

    /**
     * Quando a nota foi lançada.
     *
     * @param owner dona
     * @param key   chave
     * @return o instante no formato do Node, ou vazio
     */
    Optional<String> importedAt(OwnerId owner, String key);

    /**
     * Registra a nota; se já existir, não faz nada.
     *
     * @param owner dona
     * @param note  dados da nota
     * @return verdadeiro se registrou (falso = nota repetida)
     */
    boolean register(OwnerId owner, Note note);

    /**
     * Preço atual da embalagem do ingrediente.
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @return o preço, ou vazio se não for da dona
     */
    Optional<PriceState> price(OwnerId owner, long ingredient);

    /**
     * Soma no estoque e troca o custo (nulo = mantém).
     *
     * @param owner      dona
     * @param ingredient ingrediente
     * @param qty        embalagens a somar
     * @param cost       custo novo da embalagem
     * @return o preço depois, ou vazio se o ingrediente não for da dona
     */
    Optional<PriceState> addStock(OwnerId owner, long ingredient, double qty, Double cost);

    /**
     * Lembra que este item da nota é este ingrediente.
     *
     * @param owner      dona
     * @param itemKey    palavras do item
     * @param ingredient ingrediente
     */
    void remember(OwnerId owner, String itemKey, long ingredient);

    /**
     * Guarda quantos itens foram somados e criados.
     *
     * @param owner   dona
     * @param key     chave da nota
     * @param added   somados
     * @param created criados
     */
    void finish(OwnerId owner, String key, int added, int created);

    /**
     * Dados da nota lançada.
     *
     * @param key   chave
     * @param store loja (até 80)
     * @param date  data ({@code AAAA-MM-DD}) ou nula
     * @param total total, ou nulo
     */
    record Note(String key, String store, String date, Double total) {
    }
}
