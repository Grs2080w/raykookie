package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.purchasing.application.ports.PriceBook;
import br.com.kntap.raykookie.purchasing.application.ports.Suppliers;
import br.com.kntap.raykookie.purchasing.domain.TrayParser.ScrapedProduct;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Atualização diária ({@code refreshLinkedPrices}): relê o preço dos produtos ligados a ingredientes e grava
 * cotação nova quando mudou. Não mexe no preço do ingrediente (a dona decide com "usar este preço").
 */
@Component
public class RefreshLinkedPricesJob implements ScheduledJob {

    /** Log do resultado. */
    private static final Logger LOG = LoggerFactory.getLogger(RefreshLinkedPricesJob.class);

    /** Diferença mínima de preço que conta como mudança. */
    private static final double MIN_CHANGE = 0.005;

    /** Cotações e produtos. */
    private final PriceBook prices;

    /** Fornecedores. */
    private final Suppliers suppliers;

    /** Busca nas lojas Tray. */
    private final TraySearch tray;

    /**
     * Cria a tarefa.
     *
     * @param prices    cotações e produtos
     * @param suppliers fornecedores
     * @param tray      busca nas lojas Tray
     */
    public RefreshLinkedPricesJob(PriceBook prices, Suppliers suppliers, TraySearch tray) {
        this.prices = prices;
        this.suppliers = suppliers;
        this.tray = tray;
    }

    /**
     * Nome da tarefa.
     *
     * @return {@code purchasing.refresh-linked-prices}
     */
    @Override
    public String name() {
        return "purchasing.refresh-linked-prices";
    }

    /**
     * Uma vez por dia, às 4h (como no Node).
     *
     * @return {@link JobFrequency#DAILY_AT_4AM}
     */
    @Override
    public JobFrequency frequency() {
        return JobFrequency.DAILY_AT_4AM;
    }

    /**
     * Confere cada produto ligado; um site com problema é pulado no resto do dia.
     */
    @Override
    public void run() {
        Counts counts = refresh();
        LOG.info("Preços dos fornecedores: {} conferidos, {} mudaram, {} falharam", counts.checked(),
                counts.changed(), counts.failed());
    }

    /**
     * Confere todos os produtos ligados.
     *
     * @return quantos foram conferidos, quantos mudaram e quantos falharam
     */
    public Counts refresh() {
        int checked = 0;
        int changed = 0;
        int failed = 0;
        Set<Long> broken = new HashSet<>();
        for (PriceBook.Linked product : prices.linkedForRefresh()) {
            if (broken.contains(product.supplierId())) {
                continue;
            }
            try {
                changed += refreshOne(product) ? 1 : 0;
                checked++;
            } catch (RuntimeException error) {
                failed++;
                broken.add(product.supplierId());
                suppliers.markError(product.supplierId(), NodeInput.cut(String.valueOf(error.getMessage()), 200));
            }
        }
        return new Counts(checked, changed, failed);
    }

    /**
     * Confere um produto.
     *
     * @param product o produto
     * @return verdadeiro se o preço mudou (e gravou cotação nova)
     */
    private boolean refreshOne(PriceBook.Linked product) {

        // ── 1. Procura o produto pelo nome na loja e acha o mesmo link.
        Optional<ScrapedProduct> found = tray.search(product.base(), product.name()).stream()
                .filter(p -> p.url().equals(product.url())).findFirst();
        suppliers.markOk(product.supplierId());
        prices.markSeen(product.id(), found.map(ScrapedProduct::available).orElse(false));
        if (found.isEmpty() || Math.abs(found.get().price() - product.price()) < MIN_CHANGE) {
            return false;
        }

        // ── 2. Preço novo: atualiza o produto e grava a cotação.
        prices.updateProductPrice(product.id(), found.get().price());
        prices.addQuote(new PriceBook.Quote(product.owner(), product.ingredientId(), product.supplierId(), product.id(),
                found.get().price(), product.size(), product.unit(), "scraper"));
        return true;
    }

    /**
     * Resultado da atualização.
     *
     * @param checked conferidos
     * @param changed com preço novo
     * @param failed  que falharam
     */
    public record Counts(int checked, int changed, int failed) {
    }
}
