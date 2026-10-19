package br.com.kntap.raykookie.orders.application;

import br.com.kntap.raykookie.audit.AuditTrail;
import br.com.kntap.raykookie.audit.AuditTrail.Entry;
import br.com.kntap.raykookie.audit.AuditValues;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.StoreClock;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.ManualOrders;
import br.com.kntap.raykookie.orders.application.ports.OrderNumbers;
import br.com.kntap.raykookie.orders.domain.ManualOrderForm;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
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

    /** Diferença de preço que ainda conta como o mesmo valor (arredondamento do app). */
    private static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.004");

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

    /** Histórico de auditoria. */
    private final AuditTrail audit;

    /** Campos da linha de pedido que o histórico guarda. */
    private static final List<String> TRACKED = List.of("number", "customer", "product", "qty", "unit_price", "status",
            "payment", "order_date", "delivery_date");

    /** Campos que mexem no dinheiro do pedido. */
    private static final List<String> MONEY = List.of("qty", "unit_price", "payment");

    /**
     * Cria os casos de uso.
     *
     * @param orders        linhas da tela Pedidos
     * @param numbers       numeração
     * @param statusChanges avisos de mudança de situação
     * @param clock         relógio
     * @param audit         histórico de auditoria
     */
    public ManualOrderUseCases(ManualOrders orders, OrderNumbers numbers, StatusChanges statusChanges, Clock clock,
            AuditTrail audit) {
        this.orders = orders;
        this.numbers = numbers;
        this.statusChanges = statusChanges;
        this.clock = clock;
        this.audit = audit;
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
        Map<String, Object> row = orders.create(owner, numbers.next(owner), form);
        record(owner, "criou", null, row);
        return row;
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
        Map<String, Object> was = line(owner, id);
        Map<String, Object> row = change(owner, id, body);
        record(owner, "editou", was, row);
        return row;
    }

    /**
     * Aplica a edição (troca rápida ou completa).
     *
     * @param owner dona
     * @param id    linha
     * @param body  corpo JSON
     * @return a linha atualizada
     */
    private Map<String, Object> change(OwnerId owner, long id, Map<String, Object> body) {

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
                    || form.unitPrice().subtract(app.unitPrice()).abs().compareTo(PRICE_TOLERANCE) > 0;
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
    @Transactional
    public void delete(OwnerId owner, long id) {
        if (orders.appLine(id, owner).isPresent()) {
            throw new ConflictException("Pedido do app não pode ser excluído. Use \"Cancelar pedido\" se ele não vai "
                    + "acontecer.");
        }
        Map<String, Object> was = line(owner, id);
        orders.delete(id, owner);
        record(owner, "excluiu", was, null);
    }

    /**
     * A linha como está agora (o "antes" do histórico).
     *
     * @param owner dona
     * @param id    linha
     * @return a linha, ou {@code null}
     */
    private Map<String, Object> line(OwnerId owner, long id) {
        return orders.list(owner).stream().filter(r -> r.get("id") instanceof Number n && n.longValue() == id)
                .findFirst().orElse(null);
    }

    /**
     * Grava o histórico do pedido; se o dinheiro mudou (quantidade, preço, pagamento), grava também no financeiro.
     *
     * @param owner  dona
     * @param action criou, editou ou excluiu
     * @param was    a linha de antes (ou {@code null})
     * @param now    a linha de depois (ou {@code null})
     */
    private void record(OwnerId owner, String action, Map<String, Object> was, Map<String, Object> now) {
        Map<String, Object> ref = now != null ? now : was;
        Object id = ref == null ? "?" : ref.get("id");
        String label = "Pedido #" + (ref == null ? id : ref.get("number")) + " de "
                + (ref == null ? "?" : ref.get("customer"));
        audit.record(owner, Entry.of("pedido", action, "order", id, AuditValues.pick(was, TRACKED),
                AuditValues.pick(now, TRACKED), label + ": " + action));
        if (now != null && AuditValues.changed(was, now, MONEY)) {
            String paid = String.valueOf(now.get("payment"));
            String how = was == null ? "criou" : "pago".equals(paid) ? "pagou" : "editou";
            audit.record(owner, Entry.of("financeiro", how, "order", id, AuditValues.pick(was, MONEY),
                    AuditValues.pick(now, MONEY), label + ": " + AuditValues.num(now.get("qty")) + " x R$ "
                            + AuditValues.num(now.get("unit_price")) + ", pagamento " + paid));
        }
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
