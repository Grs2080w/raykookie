package br.com.kntap.raykookie.customers.adapter.out.persistence;

import br.com.kntap.raykookie.customers.api.CustomersApi;
import br.com.kntap.raykookie.customers.api.CustomersApi.CustomerSummary;
import br.com.kntap.raykookie.customers.application.ports.CustomerAccounts;
import br.com.kntap.raykookie.customers.domain.Profile;
import br.com.kntap.raykookie.kernel.ConflictException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clientes nas tabelas do Node, com o mesmo SQL de {@code backend/lib/customer-auth.ts} e
 * {@code backend/lib/customer-privacy.ts}. A exclusão de conta mexe também em pedidos, sessões e aparelhos:
 * é uma operação só, numa transação, como no Node.
 */
@Repository
public class JdbcCustomerAccounts implements CustomerAccounts, CustomersApi {

    /** Colunas do perfil ({@code COLS} do Node). */
    private static final String PROFILE = "id, email, name, phone, cep, street, number, complement, district, city";

    /** Pedidos em andamento de um cliente. */
    private static final String OPEN_ORDERS = """
            SELECT count(DISTINCT so.id)::int AS n FROM shop_orders so
              JOIN orders o ON o.shop_order_id = so.id
             WHERE so.customer_id = :id AND so.cancelled_at IS NULL AND o.status NOT IN ('entregue','cancelado')""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public JdbcCustomerAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Login> loginByEmail(String email) {
        return jdbc.sql("SELECT id, password_hash FROM customers WHERE lower(email) = lower(:email) AND NOT guest")
                .param("email", email)
                .query((rs, row) -> new Login(new CustomerId(rs.getLong("id")), rs.getString("password_hash")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public CustomerId create(String name, String email, String passwordHash) {
        try {
            return new CustomerId(jdbc.sql("INSERT INTO customers (email, name, password_hash) "
                            + "VALUES (:email, :name, :hash) RETURNING id")
                    .param("email", email).param("name", name).param("hash", passwordHash)
                    .query(Long.class).single());
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException("Já existe uma conta com esse e-mail. Entre com ela ou use o Google.");
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public CustomerId upsertGoogle(String sub, String email, String name) {

        // ── 1. Já ligada a esta conta Google.
        Optional<Long> bySub = jdbc.sql("SELECT id FROM customers WHERE google_sub = :sub")
                .param("sub", sub).query(Long.class).optional();
        if (bySub.isPresent()) {
            return new CustomerId(bySub.get());
        }

        // ── 2. Conta com o mesmo e-mail (não convidada): liga ao Google.
        Optional<Long> byEmail = jdbc.sql("UPDATE customers SET google_sub = :sub "
                        + "WHERE lower(email) = lower(:email) AND NOT guest RETURNING id")
                .param("sub", sub).param("email", email).query(Long.class).optional();
        if (byEmail.isPresent()) {
            return new CustomerId(byEmail.get());
        }

        // ── 3. Conta nova.
        String displayName = name.isEmpty() ? email.substring(0, email.indexOf('@')) : name;
        return new CustomerId(jdbc.sql("INSERT INTO customers (email, name, google_sub) VALUES (:email, :name, :sub) "
                        + "RETURNING id")
                .param("email", email).param("name", displayName).param("sub", sub).query(Long.class).single());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> profile(CustomerId id) {
        return jdbc.sql("SELECT " + PROFILE + " FROM customers WHERE id = :id")
                .param("id", id.value()).query(NodeRows.ROW).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(CustomerId id, Profile p) {
        jdbc.sql("""
                UPDATE customers SET name = :name, phone = :phone, cep = :cep, street = :street, number = :number,
                       complement = :complement, district = :district, city = :city
                 WHERE id = :id AND deleted_at IS NULL""")
                .param("id", id.value()).param("name", p.name()).param("phone", p.phone()).param("cep", p.cep())
                .param("street", p.street()).param("number", p.number()).param("complement", p.complement())
                .param("district", p.district()).param("city", p.city())
                .update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Map<String, Object>> myData(CustomerId id) {

        // ── 1. A conta, se não foi excluída.
        Optional<Map<String, Object>> account = jdbc.sql("""
                SELECT id, email, name, phone, cep, street, number, complement, district, city, created_at,
                       (password_hash IS NOT NULL) AS has_password, (google_sub IS NOT NULL) AS has_google
                  FROM customers WHERE id = :id AND deleted_at IS NULL""")
                .param("id", id.value()).query(NodeRows.ROW).optional();
        if (account.isEmpty()) {
            return account;
        }

        // ── 2. As contagens, na ordem do Node.
        Map<String, Object> data = new LinkedHashMap<>(account.get());
        data.put("orders", count("SELECT count(*)::int FROM shop_orders WHERE customer_id = :id", id));
        data.put("open_requests",
                count("SELECT count(*)::int FROM privacy_requests WHERE customer_id = :id AND status = 'aberta'", id));
        data.put("open_orders", openOrders(id));
        return Optional.of(data);
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> requestsOf(CustomerId id) {
        return jdbc.sql("SELECT id, type, details, status, answer, created_at, answered_at FROM privacy_requests "
                        + "WHERE customer_id = :id ORDER BY created_at DESC LIMIT 20")
                .param("id", id.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> exportOrders(CustomerId id) {
        return jdbc.sql("""
                SELECT id, public_code AS code, created_at, scheduled_for, time_window, fulfillment, cep, street,
                       number, complement, district, city, payment_method, total, items, notes, paid_at, cancelled_at,
                       cancel_reason, pickup_address
                  FROM shop_orders WHERE customer_id = :id ORDER BY created_at""")
                .param("id", id.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> exportRequests(CustomerId id) {
        return jdbc.sql("SELECT type, details, status, answer, created_at, answered_at FROM privacy_requests "
                        + "WHERE customer_id = :id ORDER BY created_at")
                .param("id", id.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> exportDevices(CustomerId id) {
        return jdbc.sql("SELECT platform, created_at, last_seen FROM customer_devices WHERE customer_id = :id "
                        + "ORDER BY created_at")
                .param("id", id.value()).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Link> link(CustomerId id) {
        return jdbc.sql("SELECT password_hash IS NOT NULL AS has_password, google_sub IS NOT NULL AS has_google "
                        + "FROM customers WHERE id = :id")
                .param("id", id.value())
                .query((rs, row) -> new Link(rs.getBoolean("has_password"), rs.getBoolean("has_google")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void unlinkGoogle(CustomerId id) {
        jdbc.sql("UPDATE customers SET google_sub = NULL WHERE id = :id").param("id", id.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Login> activeAccount(CustomerId id) {
        return jdbc.sql("SELECT id, password_hash FROM customers WHERE id = :id AND deleted_at IS NULL")
                .param("id", id.value())
                .query((rs, row) -> new Login(id, rs.getString("password_hash")))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public int openOrders(CustomerId id) {
        return count(OPEN_ORDERS, id);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void anonymize(CustomerId id, Optional<OwnerId> owner) {

        // ── 1. Pedidos: ficam (obrigação fiscal), sem nada que identifique a pessoa.
        jdbc.sql("""
                UPDATE orders o
                   SET customer = 'Cliente excluído', address = '', notes = 'Pedido online #' || so.public_code
                  FROM shop_orders so WHERE o.shop_order_id = so.id AND so.customer_id = :id""")
                .param("id", id.value()).update();
        jdbc.sql("""
                UPDATE shop_orders SET contact_name = 'Cliente excluído', phone = '', cep = '', street = '',
                       number = '', complement = '', district = '', city = '', notes = '', pickup_address = '',
                       cancel_note = ''
                 WHERE customer_id = :id""")
                .param("id", id.value()).update();

        // ── 2. Sessões e aparelhos: nenhum acesso nem aviso chega mais a esta pessoa.
        jdbc.sql("DELETE FROM customer_sessions WHERE customer_id = :id").param("id", id.value()).update();
        jdbc.sql("DELETE FROM customer_devices WHERE customer_id = :id").param("id", id.value()).update();

        // ── 3. A conta vira anônima.
        jdbc.sql("""
                UPDATE customers SET email = 'excluido-' || id || '@invalid', name = 'Cliente excluído', phone = '',
                       cep = '', street = '', number = '', complement = '', district = '', city = '',
                       password_hash = NULL, google_sub = NULL, deleted_at = now()
                 WHERE id = :id""")
                .param("id", id.value()).update();

        // ── 4. Registro da exclusão para a dona.
        owner.ifPresent(o -> jdbc.sql("""
                INSERT INTO privacy_requests (owner_id, customer_id, type, details, status, answer, answered_at)
                VALUES (:owner, :id, 'exclusao', 'Exclusão feita pelo próprio cliente', 'concluida',
                        'Conta anonimizada automaticamente.', now())""")
                .param("owner", o.value()).param("id", id.value()).update());
    }

    /** {@inheritDoc} */
    @Override
    public long createRequest(OwnerId owner, CustomerId customer, String type, String details) {
        return jdbc.sql("INSERT INTO privacy_requests (owner_id, customer_id, type, details) "
                        + "VALUES (:owner, :customer, :type, :details) RETURNING id")
                .param("owner", owner.value()).param("customer", customer.value())
                .param("type", type).param("details", details)
                .query(Long.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> requestsFor(OwnerId owner, int deadlineDays) {
        return jdbc.sql("""
                SELECT r.id, r.type, r.details, r.status, r.answer, r.created_at, r.answered_at,
                       c.name AS customer, c.email,
                       (r.created_at + make_interval(days => :days)) AS due_at
                  FROM privacy_requests r JOIN customers c ON c.id = r.customer_id
                 WHERE r.owner_id = :owner
                 ORDER BY (r.status = 'aberta') DESC, r.created_at DESC LIMIT 100""")
                .param("owner", owner.value()).param("days", deadlineDays)
                .query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public boolean answer(long id, OwnerId owner, String answer) {
        return jdbc.sql("""
                UPDATE privacy_requests SET status = 'concluida', answer = :answer, answered_at = now()
                 WHERE id = :id AND owner_id = :owner AND status = 'aberta'""")
                .param("id", id).param("owner", owner.value()).param("answer", answer)
                .update() > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CustomerSummary> summary(CustomerId id) {
        return jdbc.sql("SELECT id, email, name, phone, guest, created_at FROM customers WHERE id = :id")
                .param("id", id.value())
                .query((rs, row) -> new CustomerSummary(id, rs.getString("email"), rs.getString("name"),
                        rs.getString("phone"), rs.getBoolean("guest"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CustomerSummary> byId(CustomerId id) {
        return summary(id);
    }

    /**
     * Uma contagem por cliente.
     *
     * @param sql consulta com {@code :id}
     * @param id  cliente
     * @return a contagem
     */
    private int count(String sql, CustomerId id) {
        return jdbc.sql(sql).param("id", id.value()).query(Integer.class).single();
    }
}
