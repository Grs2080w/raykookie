package br.com.kntap.raykookie.audit;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Grava o histórico na tabela {@code audit_log} (migration V30). Só insere: a tabela tem gatilhos que recusam
 * {@code UPDATE}, {@code DELETE} e {@code TRUNCATE}, e este adaptador não tem nenhum método que altere ou apague.
 */
@Component
public class JdbcAuditTrail implements AuditTrail {

    /** Nova linha. */
    private static final String INSERT = """
            INSERT INTO audit_log (owner_id, user_id, user_name, role, action, category, location, route,
                                   record_kind, record_id, old_value, new_value, ip, details)
            VALUES (:owner, :user, :userName, :role, :action, :category, :location, :route,
                    :recordType, :recordId, CAST(:oldValue AS jsonb), CAST(:newValue AS jsonb), :ip, :details)""";

    /** Banco. */
    private final JdbcClient jdbc;

    /** JSON dos valores antes/depois. */
    private final ObjectMapper json;

    /** IP real de quem chamou (atrás do proxy de confiança). */
    private final ClientIpResolver ips;

    /** Quem está chamando a requisição. */
    private final CurrentActor actor;

    /**
     * Quem agiu.
     *
     * @param id   id da conta (0 para o sistema e para quem não tem conta)
     * @param name nome
     * @param role {@code dona}, {@code cliente}, {@code sistema} ou {@code visitante}
     */
    private record Who(long id, String name, String role) {
    }

    /**
     * Cria o adaptador.
     *
     * @param jdbc  cliente JDBC
     * @param json  JSON
     * @param ips   resolvedor do IP do cliente
     * @param actor quem está chamando
     */
    public JdbcAuditTrail(JdbcClient jdbc, ObjectMapper json, ClientIpResolver ips, CurrentActor actor) {
        this.jdbc = jdbc;
        this.json = json;
        this.ips = ips;
        this.actor = actor;
    }

    /** {@inheritDoc} */
    @Override
    public void record(OwnerId owner, Entry entry) {

        // ── 1. O histórico só vale junto com a ação: sem transação aberta é erro de programação, não silêncio.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("O histórico precisa ser gravado dentro da transação da ação ("
                    + entry.category() + "/" + entry.action() + ")");
        }

        // ── 2. De onde veio: rota e IP da requisição em andamento (tarefas agendadas não têm).
        HttpServletRequest request = currentRequest();
        String route = request == null ? "(sistema)" : request.getMethod() + " " + request.getRequestURI();
        String location = entry.location() != null ? entry.location()
                : AuditLocations.of(request == null ? null : request.getRequestURI());

        // ── 3. Quem fez.
        Who who = who(owner, request);

        // ── 4. Grava, sem segredos nos valores.
        jdbc.sql(INSERT)
                .param("owner", owner.value()).param("user", who.id()).param("userName", who.name())
                .param("role", who.role()).param("action", entry.action()).param("category", entry.category())
                .param("location", location).param("route", route).param("recordType", entry.recordType())
                .param("recordId", entry.recordId())
                .param("oldValue", toJson(AuditRedactor.redact(entry.before())))
                .param("newValue", toJson(AuditRedactor.redact(entry.after())))
                .param("ip", request == null ? null : ips.resolve(request)).param("details", entry.details())
                .update();
    }

    /**
     * Quem fez a ação: a dona (sessão do painel), o cliente (sessão do site/app), o sistema (webhook do Mercado Pago ou
     * tarefa agendada) ou um visitante que chegou por link de pagamento.
     *
     * @param owner   a loja
     * @param request a requisição, ou {@code null}
     * @return quem agiu
     */
    private Who who(OwnerId owner, HttpServletRequest request) {
        if (request == null) {
            return new Who(0, "Sistema", "sistema");
        }
        if (request.getRequestURI().startsWith("/api/webhooks/")) {
            return new Who(0, "Mercado Pago (webhook)", "sistema");
        }
        if (actor.ownerIfAny().isPresent()) {
            long id = actor.ownerIfAny().get().value();
            String name = jdbc.sql("SELECT username FROM users WHERE id = :id").param("id", id)
                    .query(String.class).optional().orElse("conta " + id);
            return new Who(id, name, "dona");
        }
        if (actor.customerIfAny().isPresent()) {
            long id = actor.customerIfAny().get().value();
            String name = jdbc.sql("SELECT name FROM customers WHERE id = :id").param("id", id)
                    .query(String.class).optional().orElse("cliente " + id);
            return new Who(id, name, "cliente");
        }
        return new Who(0, "Visitante (link de pagamento)", "visitante");
    }

    /**
     * A requisição em andamento nesta thread.
     *
     * @return a requisição, ou {@code null} fora de uma
     */
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web
                ? web.getRequest() : null;
    }

    /**
     * Valor em JSON, ou {@code null} se não houver.
     *
     * @param values valores
     * @return o texto JSON
     */
    private String toJson(Object values) {
        return values == null ? null : json.writeValueAsString(values);
    }
}
