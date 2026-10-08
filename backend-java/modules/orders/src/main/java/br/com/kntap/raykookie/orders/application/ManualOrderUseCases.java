package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.ManualOrders;
import br.com.kntap.raykookie.orders.application.ports.OrderNumbers;
import br.com.kntap.raykookie.orders.domain.ManualOrderForm;
import br.com.kntap.raykookie.web.NodeInput;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tela Pedidos da dona ({@code /api/orders}): pedidos registrados à mão e as linhas dos pedidos da loja, que têm
 * regras próprias (não somem, não mudam item nem valor, não reabrem e só cancelam pelo botão próprio).
 */
@Service
public class ManualOrderUseCases {

    /** Cancelamento de pedido da loja só pelo botão próprio. */
    static final String USE_CANCEL = "Pedido do app: use \"Cancelar pedido\", que pede o motivo para a cliente e "
            + "devolve o Pix se já foi pago.";

    /** Pedido da loja encerrado não muda mais. */
    static final String CLOSED = "Este pedido do app já foi encerrado (cancelado ou devolvido) e não muda mais.";

    /** Pedido que não é da dona. */
    static final String NOT_FOUND = "Pedido não encontrado.";

    /** Linhas da tela Pedidos. */
    private final ManualOrders orders;

    /** Numeração. */
    private final OrderNumbers numbers;

    /** Avisos de mudança de situação. */
    private final StatusChanges statusChanges;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria os casos de uso.
     *
     * @param orders        linhas da tela Pedidos
     * @param numbers       numeração
     * @param statusChanges avisos de mudança de situação
     * @param clock         relógio
     */
    public ManualOrderUseCases(ManualOrders orders, OrderNumbers numbers, StatusChanges statusChanges, Clock clock) {
        this.orders = orders;
        this.numbers = numbers;
        this.statusChanges = statusChanges;
        this.clock = clock;
    }

    /**
     * Pedidos da dona, mais novos primeiro.
     *
     * @param owner dona
     * @return as linhas
     */
    public List<Map<String, Object>> list(OwnerId owner) {
        return orders.list(owner);
    }

    /**
     * Registra um pedido à mão, com o próximo número da dona (mesma trava dos pedidos da loja, G10).
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada
     */
    @Transactional
    public Map<String, Object> create(OwnerId owner, Map<String, Object> body) {
        ManualOrderForm form = ManualOrderForm.from(body, today());
        numbers.lock(owner);
        return orders.create(owner, numbers.next(owner), form);
    }

    /**
     * Edita uma linha: troca rápida de situação/pagamento ou edição completa.
     *
     * @param owner dona
     * @param id    linha
     * @param body  corpo JSON
     * @return a linha atualizada
     */
    @Transactional
    public Map<String, Object> update(OwnerId owner, long id, Map<String, Object> body) {

        // ── 1. Pedido da loja encerrado não muda mais.
        Optional<ManualOrders.AppLine> line = orders.appLine(id, owner);
        if (line.isPresent() && line.get().closed()) {
            throw new ConflictException(CLOSED);
        }

        // ── 2. Troca rápida (só situação e/ou pagamento).
        boolean quick = (body.containsKey("status") || body.containsKey("payment")) && body.size() <= 2
                && !body.containsKey("customer");
        if (quick) {
            return quickUpdate(owner, id, body, line.isPresent());
        }

        // ── 3. Edição completa.
        return fullUpdate(owner, id, ManualOrderForm.from(body, today()), line);
    }

    /**
     * Troca rápida.
     *
     * @param owner   dona
     * @param id      linha
     * @param body    corpo
     * @param appLine se é linha de pedido da loja
     * @return a linha atualizada
     */
    private Map<String, Object> quickUpdate(OwnerId owner, long id, Map<String, Object> body, boolean appLine) {

        // ── 1. Pedido da loja só cancela pelo botão próprio.
        if ("cancelado".equals(body.get("status")) && appLine) {
            throw new ConflictException(USE_CANCEL);
        }
        Optional<ManualOrders.Snapshot> before = orders.snapshot(id, owner);
        String status = NodeInput.truthy(body.get("status")) ? NodeInput.text(body.get("status")) : null;
        String payment = NodeInput.truthy(body.get("payment")) ? NodeInput.text(body.get("payment")) : null;

        // ── 2. Atualiza; a guarda do SQL cobre a corrida com a promoção pelo link.
        Optional<Map<String, Object>> row = orders.quickUpdate(id, owner, status, payment);
        if (row.isPresent()) {
            statusChanges.after(before);
            return row.get();
        }
        throw orders.exists(id, owner) ? new ConflictException(USE_CANCEL) : new NotFoundException(NOT_FOUND);
    }

    /**
     * Edição completa: linha de pedido da loja não muda item nem valor e não cancela.
     *
     * @param owner dona
     * @param id    linha
     * @param form  dados
     * @param line  linha de pedido da loja (vazio = pedido à mão)
     * @return a linha atualizada
     */
    private Map<String, Object> fullUpdate(OwnerId owner, long id, ManualOrderForm form,
            Optional<ManualOrders.AppLine> line) {

        // ── 1. As regras das linhas de pedido da loja.
        if (line.isPresent()) {
            ManualOrders.AppLine app = line.get();
            if ("cancelado".equals(form.status()) && !"cancelado".equals(app.status())) {
                throw new ConflictException(USE_CANCEL);
            }
            boolean changed = !form.product().equals(app.product()) || form.qty().compareTo(app.qty()) != 0
                    || form.unitPrice().subtract(app.unitPrice()).abs().compareTo(new java.math
                            .BigDecimal("0.004")) > 0;
            if (changed) {
                throw new ConflictException("Itens e valores de pedido do app não mudam por aqui. Para devolver parte "
                        + "do valor, use \"Devolver valor\".");
            }
        }

        // ── 2. Atualiza e acompanha o pedido promovido pelo link.
        Optional<ManualOrders.Snapshot> before = orders.snapshot(id, owner);
        Map<String, Object> row = orders.update(id, owner, form).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        orders.followManualShopOrder(id, form);
        statusChanges.after(before);
        return row;
    }

    /**
     * Exclui um pedido à mão; linha de pedido da loja não some.
     *
     * @param owner dona
     * @param id    linha
     */
    public void delete(OwnerId owner, long id) {
        if (orders.appLine(id, owner).isPresent()) {
            throw new ConflictException("Pedido do app não pode ser excluído. Use \"Cancelar pedido\" se ele não vai "
                    + "acontecer.");
        }
        orders.delete(id, owner);
    }

    /**
     * Hoje no fuso da loja.
     *
     * @return a data
     */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(StoreClock.ZONE));
    }
}
