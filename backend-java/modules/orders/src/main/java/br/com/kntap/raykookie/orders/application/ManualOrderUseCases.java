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
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.orders.domain.ManualOrderForm;
import br.com.kntap.raykookie.orders.domain.ManualOrderItems;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
     * Registra um pedido à mão, com o próximo número da dona (mesma trava dos pedidos da loja, G10). Com {@code items}
     * no corpo, cada item vira uma linha (com o seu número) e as linhas do pedido compartilham o {@code group_id}.
     *
     * @param owner dona
     * @param body  corpo JSON
     * @return a linha criada (a do primeiro item, quando há vários)
     */
    @Transactional
    public Map<String, Object> create(OwnerId owner, Map<String, Object> body) {

        // ── 1. Um item só (corpo antigo ou lista de um) é a linha sem grupo.
        List<ManualOrderItems> items = ManualOrderItems.from(body, today());
        List<ManualOrderForm> forms = items.isEmpty() ? List.of(ManualOrderForm.from(body, today()))
                : items.stream().map(ManualOrderItems::form).toList();
        UUID group = forms.size() > 1 ? UUID.randomUUID() : null;

        // ── 2. Uma linha por item, com o próximo número de cada uma.
        numbers.lock(owner);
        Map<String, Object> first = null;
        for (ManualOrderForm form : forms) {
            Map<String, Object> row = orders.create(owner, numbers.next(owner), form, group);
            record(owner, "criou", null, row);
            first = first == null ? row : first;
        }
        return first;
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
        List<ManualOrderItems> items = ManualOrderItems.from(body, today());
        if (!items.isEmpty()) {
            return updateItems(owner, id, items);
        }
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
            followGroup(owner, id, status, payment);
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
     * Troca rápida em pedido com vários itens: as outras linhas do pedido acompanham.
     *
     * @param owner   dona
     * @param id      linha trocada
     * @param status  situação nova (nula = mantém)
     * @param payment pagamento novo (nulo = mantém)
     */
    private void followGroup(OwnerId owner, long id, String status, String payment) {
        for (long other : orders.groupLines(id, owner)) {
            if (other != id) {
                Map<String, Object> was = line(owner, other);
                orders.quickUpdate(other, owner, status, payment).ifPresent(row -> record(owner, "editou", was, row));
            }
        }
    }

    /**
     * Confere que o pedido existe, é à mão e que os ids dos itens enviados são dele.
     *
     * @param owner    dona
     * @param existing linhas do pedido hoje
     * @param items    itens enviados
     */
    private void checkEditable(OwnerId owner, List<Long> existing, List<ManualOrderItems> items) {
        if (existing.isEmpty()) {
            throw new NotFoundException(NOT_FOUND);
        }
        for (long lineId : existing) {
            if (orders.appLine(lineId, owner).isPresent()) {
                throw new ConflictException("Itens e valores de pedido do app não mudam por aqui. Para devolver "
                        + "parte do valor, use \"Devolver valor\".");
            }
        }
        List<Long> sent = items.stream().map(ManualOrderItems::id).filter(java.util.Objects::nonNull).toList();
        if (!existing.containsAll(sent) || sent.size() != sent.stream().distinct().count()) {
            throw new InvalidInputException("Item inválido.");
        }
    }

    /**
     * Edição de pedido à mão com a lista de itens: atualiza os itens que já existem, cria os novos e tira os que
     * saíram da lista. Pedido do app não muda item nem valor por aqui.
     *
     * @param owner dona
     * @param id    uma das linhas do pedido
     * @param items itens como ficam depois da edição
     * @return a linha do primeiro item
     */
    private Map<String, Object> updateItems(OwnerId owner, long id, List<ManualOrderItems> items) {

        // ── 1. O pedido é da dona, é à mão e os ids enviados são dele.
        List<Long> existing = orders.groupLines(id, owner);
        checkEditable(owner, existing, items);

        // ── 2. Atualiza ou cria cada item, na ordem enviada.
        numbers.lock(owner);
        UUID group = items.size() > 1 ? UUID.randomUUID() : null;
        List<Long> kept = new ArrayList<>();
        for (ManualOrderItems item : items) {
            if (item.id() == null) {
                Map<String, Object> row = orders.create(owner, numbers.next(owner), item.form(), group);
                record(owner, "criou", null, row);
                kept.add(((Number) row.get("id")).longValue());
            } else {
                Map<String, Object> was = line(owner, item.id());
                Map<String, Object> row = orders.update(item.id(), owner, item.form())
                        .orElseThrow(() -> new NotFoundException(NOT_FOUND));
                orders.followManualShopOrder(item.id(), item.form());
                record(owner, "editou", was, row);
                kept.add(item.id());
            }
        }

        // ── 3. Quem saiu da lista é excluído; o resto fica no mesmo pedido.
        for (long lineId : existing) {
            if (!kept.contains(lineId)) {
                Map<String, Object> was = line(owner, lineId);
                orders.delete(lineId, owner);
                record(owner, "excluiu", was, null);
            }
        }
        orders.setGroup(kept, owner, group);
        return line(owner, kept.getFirst());
    }

    /**
     * Exclui um pedido à mão; linha de pedido da loja não some.
     *
     * @param owner dona
     * @param id    linha
     */
    @Transactional
    public void delete(OwnerId owner, long id) {

        // ── 1. Pedido do app não some; pedido com vários itens some inteiro.
        List<Long> lines = orders.groupLines(id, owner);
        for (long lineId : lines.isEmpty() ? List.of(id) : lines) {
            if (orders.appLine(lineId, owner).isPresent()) {
                throw new ConflictException("Pedido do app não pode ser excluído. Use \"Cancelar pedido\" se ele não "
                        + "vai acontecer.");
            }
        }

        // ── 2. Exclui cada linha, com o histórico de cada uma.
        for (long lineId : lines.isEmpty() ? List.of(id) : lines) {
            Map<String, Object> was = line(owner, lineId);
            orders.delete(lineId, owner);
            record(owner, "excluiu", was, null);
        }
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
