package br.com.kntap.raykookie.orders.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.domain.ManualOrderForm;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Linhas da tela Pedidos (tabela {@code orders}), registradas à mão ou espelhando pedidos da loja.
 */
public interface ManualOrders {

    /**
     * Pedidos da dona com os dados do pedido da loja ({@code getOrders}).
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Cria um pedido registrado à mão.
     *
     * @param owner  dona
     * @param number número do pedido
     * @param form   dados
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, int number, ManualOrderForm form);

    /**
     * A linha, se espelha um pedido da loja.
     *
     * @param id    linha
     * @param owner dona
     * @return quantidade, preço, produto, situação e se o pedido da loja foi encerrado
     */
    Optional<AppLine> appLine(long id, OwnerId owner);

    /**
     * Troca rápida de situação e/ou pagamento (nunca cancela linha de pedido da loja).
     *
     * @param id      linha
     * @param owner   dona
     * @param status  situação nova (nula = mantém)
     * @param payment pagamento novo (nulo = mantém)
     * @return a linha atualizada, ou vazio
     */
    Optional<Map<String, Object>> quickUpdate(long id, OwnerId owner, String status, String payment);

    /**
     * Diz se a linha é da dona.
     *
     * @param id    linha
     * @param owner dona
     * @return verdadeiro se for
     */
    boolean exists(long id, OwnerId owner);

    /**
     * Edição completa.
     *
     * @param id    linha
     * @param owner dona
     * @param form  dados
     * @return a linha atualizada, ou vazio
     */
    Optional<Map<String, Object>> update(long id, OwnerId owner, ManualOrderForm form);

    /**
     * Pedido à mão promovido pelo link: data e nome seguem o que a dona edita.
     *
     * @param id   linha
     * @param form dados
     */
    void followManualShopOrder(long id, ManualOrderForm form);

    /**
     * Apaga a linha.
     *
     * @param id    linha
     * @param owner dona
     */
    void delete(long id, OwnerId owner);

    /**
     * Pedido da loja ao qual a linha pertence e a situação de cada item, antes de mudar.
     *
     * @param id    linha
     * @param owner dona
     * @return pedido da loja e situações, se a linha for de um pedido da loja
     */
    Optional<Snapshot> snapshot(long id, OwnerId owner);

    /**
     * Linha de pedido da loja.
     *
     * @param qty       quantidade
     * @param unitPrice preço unitário
     * @param product   produto
     * @param status    situação
     * @param closed    se o pedido da loja foi encerrado
     */
    record AppLine(BigDecimal qty, BigDecimal unitPrice, String product, String status, boolean closed) {
    }

    /**
     * Situação de um pedido da loja antes de uma mudança.
     *
     * @param shopOrderId pedido da loja
     * @param statuses    situação de cada item
     */
    record Snapshot(long shopOrderId, List<String> statuses) {
    }
}
