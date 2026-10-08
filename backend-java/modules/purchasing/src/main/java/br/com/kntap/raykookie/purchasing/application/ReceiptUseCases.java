package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.catalog.api.CatalogApi;
import br.com.kntap.raykookie.catalog.api.PriceChange;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import br.com.kntap.raykookie.purchasing.application.ports.Receipts;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import br.com.kntap.raykookie.purchasing.domain.NfceParser;
import br.com.kntap.raykookie.purchasing.domain.NfceParser.NfceItem;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Nota fiscal: leitura (o app manda o HTML da página do QR code; o servidor não acessa a SEFAZ) e lançamento no
 * estoque ({@code /api/nota/ler} e {@code /api/nota/importar}).
 */
@Service
public class ReceiptUseCases {

    /** Página menor que isto não é uma nota. */
    private static final int MIN_PAGE = 200;

    /** Itens por nota. */
    private static final int MAX_ITEMS = 300;

    /** Notas e estoque. */
    private final Receipts receipts;

    /** Lançamento numa transação. */
    private final ReceiptImporter importer;

    /** Catálogo (histórico e alertas de margem). */
    private final CatalogApi catalog;

    /**
     * Cria os casos de uso.
     *
     * @param receipts notas e estoque
     * @param importer lançamento
     * @param catalog  catálogo
     */
    public ReceiptUseCases(Receipts receipts, ReceiptImporter importer, CatalogApi catalog) {
        this.receipts = receipts;
        this.importer = importer;
        this.catalog = catalog;
    }

    /**
     * Lê a nota e casa cada item com o estoque.
     *
     * @param owner dona
     * @param body  {@code url} (link do QR code) e {@code html} (a página)
     * @return chave, loja, data, total, se já foi lançada e os itens com as sugestões
     */
    public Map<String, Object> read(OwnerId owner, Map<String, Object> body) {

        // ── 1. Link de portal .gov.br e página de tamanho razoável.
        String url = NodeInput.cut(NodeInput.text(body.get("url")), 2000);
        String html = body.get("html") instanceof String page ? page : "";
        if (!url.isEmpty() && !NfceParser.isNfceUrl(url)) {
            throw new InvalidInputException("Esse QR code não é de uma nota fiscal (o link precisa ser de um portal "
                    + ".gov.br).");
        }
        if (html.length() < MIN_PAGE) {
            throw new InvalidInputException("Não consegui abrir a página da nota. Confira a internet e tente de novo.");
        }
        if (html.length() > NfceParser.MAX_PAGE) {
            throw new InvalidInputException("A página da nota veio grande demais para ler.");
        }

        // ── 2. Lê; sem itens, o portal pediu captcha ou o layout é desconhecido.
        NfceParser.NfceParsed nota = NfceParser.parse(html, url).orElseThrow(() -> new DomainException("Não consegui "
                + "ler os itens dessa nota. O portal pode estar pedindo um código de verificação (captcha) ou ter um "
                + "formato que ainda não conheço. Tente de novo mais tarde ou cadastre os itens à mão.", 422));

        // ── 3. Casa com o estoque e monta a resposta.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", nota.key());
        json.put("store", nota.store());
        json.put("date", nota.date());
        json.put("total", nota.total() == null ? null : NodeRows.number(nota.total()));
        json.put("already_imported", receipts.importedAt(owner, nota.key()).orElse(null));
        json.put("items", items(owner, nota.items()));
        return json;
    }

    /**
     * Itens com o casamento: apelido confirmado (soma direto) ou sugestão.
     *
     * @param owner dona
     * @param items itens da nota
     * @return os itens no formato do Node
     */
    private List<Map<String, Object>> items(OwnerId owner, List<NfceItem> items) {
        List<NfceMatching.Ingredient> ingredients = receipts.ingredients(owner);
        Map<String, Long> aliases = receipts.aliases(owner);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            NfceItem item = items.get(index);
            Long aliased = aliases.get(String.join(" ", NfceMatching.nameTokens(item.name())));
            Optional<NfceMatching.Match> match = find(ingredients, aliased)
                    .map(found -> new NfceMatching.Match(found.id(), found.name(), true))
                    .or(() -> NfceMatching.matchIngredient(item.name(), ingredients));
            Optional<NfceMatching.Ingredient> target = match.flatMap(m -> find(ingredients, m.ingredientId()));
            out.add(ReceiptJson.item(index, item, match.orElse(null),
                    target.map(found -> NfceMatching.planAdd(item, found)).orElse(null), NfceMatching
                            .planCreate(item)));
        }
        return out;
    }

    /**
     * Ingrediente pelo id.
     *
     * @param ingredients lista
     * @param id          id (nulo = nenhum)
     * @return o ingrediente
     */
    private static Optional<NfceMatching.Ingredient> find(List<NfceMatching.Ingredient> ingredients, Long id) {
        return id == null ? Optional.empty() : ingredients.stream().filter(i -> i.id() == id).findFirst();
    }

    /**
     * Lança a nota no estoque de uma vez só e, depois de gravar, registra as mudanças de preço.
     *
     * @param owner dona
     * @param body  {@code key}, {@code store}, {@code date}, {@code total} e {@code items}
     * @return {@code {"added":n,"created":n}}
     */
    public Map<String, Object> importReceipt(OwnerId owner, Map<String, Object> body) {

        // ── 1. Chave e itens.
        String key = NodeInput.cut(NodeInput.text(body.get("key")), 80);
        if (key.isEmpty()) {
            throw new InvalidInputException("Nota sem identificação. Leia o QR code de novo.");
        }
        List<?> items = body.get("items") instanceof List<?> list ? list : List.of();
        if (items.isEmpty()) {
            throw new InvalidInputException("Nenhum item para lançar.");
        }
        if (items.size() > MAX_ITEMS) {
            throw new InvalidInputException("Itens demais numa nota só.");
        }

        // ── 2. Tudo numa transação; depois, histórico e alertas de margem.
        ReceiptImporter.Result result = importer.apply(owner, key, body, items);
        for (ReceiptImporter.Change change : result.changes()) {
            catalog.priceChanged(new PriceChange(owner, change.ingredientId(), change.before(), change.after(), "nota",
                    null));
        }
        return Map.of("added", result.added(), "created", result.created());
    }
}
