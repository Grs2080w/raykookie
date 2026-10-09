package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import br.com.kntap.raykookie.purchasing.domain.NfceParser.NfceItem;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Os itens da nota no JSON do Node (números inteiros sem ".0").
 */
final class ReceiptJson {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private ReceiptJson() {
    }

    /**
     * Um item com o casamento e os planos.
     *
     * @param index  posição na nota
     * @param item   item
     * @param match  casamento (nulo = nenhum)
     * @param add    o que somar (nulo = nada casou)
     * @param create sugestão de cadastro
     * @return o item
     */
    static Map<String, Object> item(int index, NfceItem item, NfceMatching.Match match, NfceMatching.AddPlan add,
            NfceMatching.CreatePlan create) {

        // ── 1. O item como a nota trouxe.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("index", index);
        json.put("name", item.name());
        json.put("code", item.code());
        json.put("qty", NodeRows.number(item.qty()));
        json.put("unit", item.unit());
        json.put("unit_price", NodeRows.number(item.unitPrice()));
        json.put("total", NodeRows.number(item.total()));

        // ── 2. O que o servidor sugere fazer com ele.
        json.put("match", match == null ? null : match(match));
        json.put("add", add == null ? null : add(add));
        json.put("create", create(create));
        return json;
    }

    /**
     * O casamento.
     *
     * @param match casamento
     * @return o JSON
     */
    private static Map<String, Object> match(NfceMatching.Match match) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ingredient_id", match.ingredientId());
        json.put("name", match.name());
        json.put("exact", match.exact());
        return json;
    }

    /**
     * O que somar.
     *
     * @param add plano
     * @return o JSON
     */
    private static Map<String, Object> add(NfceMatching.AddPlan add) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("add_qty", NodeRows.number(add.addQty()));
        json.put("package_cost", NodeRows.number(add.packageCost()));
        json.put("note", add.note());
        return json;
    }

    /**
     * O que cadastrar.
     *
     * @param create plano
     * @return o JSON
     */
    private static Map<String, Object> create(NfceMatching.CreatePlan create) {

        // ── 1. Nome e unidade.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", create.name());
        json.put("unit", create.unit());

        // ── 2. Embalagem e estoque inicial.
        json.put("package_size", NodeRows.number(create.packageSize()));
        json.put("package_cost", NodeRows.number(create.packageCost()));
        json.put("stock_qty", NodeRows.number(create.stockQty()));
        return json;
    }
}
