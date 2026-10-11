package br.com.kntap.raykookie.payments.adapter.out.persistence;

import br.com.kntap.raykookie.payments.application.ports.PaymentAttempts;
import br.com.kntap.raykookie.persistence.Jdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tentativas de cobrança ({@code payment_attempts}): o índice único parcial garante uma em andamento por pedido.
 */
@Repository
public class JdbcPaymentAttempts implements PaymentAttempts {

    /** Tentativas em análise (com pagamento no gateway). */
    private static final String OPEN = """
            SELECT id, shop_order_id, pay_link_id, gateway_id FROM payment_attempts
             WHERE state = 'pending' AND gateway_id IS NOT NULL""";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPaymentAttempts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public boolean start(Attempt a) {
        try {
            jdbc.sql("""
                    INSERT INTO payment_attempts (id, shop_order_id, pay_link_id, customer_id, client_ip, method, state)
                    VALUES (:id, :order, :link, :customer, :ip, 'cartao', 'in_flight')""")
                    .param("id", a.id()).param("order", a.shopOrderId()).param("link", a.payLinkId())
                    .param("customer", a.customerId()).param("ip", a.clientIp()).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void finish(UUID id, String state, String gatewayId) {
        jdbc.sql("""
                UPDATE payment_attempts SET state = :state, gateway_id = COALESCE(:gateway, gateway_id),
                       finished_at = now()
                 WHERE id = :id""").param("id", id).param("state", state).param("gateway", gatewayId).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Stale> stale(Instant before) {
        return jdbc.sql("""
                SELECT id, shop_order_id, pay_link_id, state FROM payment_attempts
                 WHERE created_at < :before AND created_at > now() - interval '1 day'
                   AND (state = 'in_flight' OR (state = 'error' AND gateway_id IS NULL))
                 ORDER BY created_at LIMIT 50""")
                .param("before", Jdbc.timestamp(before))
                .query((rs, row) -> new Stale(rs.getObject(1, UUID.class), rs.getObject(2) == null ? null
                        : rs.getLong(2), rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4)))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Open> open(Long shopOrderId, Long payLinkId) {
        return jdbc.sql(OPEN + " AND (shop_order_id = :order OR pay_link_id = :link) ORDER BY created_at")
                .param("order", shopOrderId).param("link", payLinkId).query(JdbcPaymentAttempts::openRow).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<UUID> unanswered(Long shopOrderId, Long payLinkId) {
        return jdbc.sql("""
                SELECT id FROM payment_attempts
                 WHERE state = 'error' AND gateway_id IS NULL AND created_at > now() - interval '1 day'
                   AND (shop_order_id = :order OR pay_link_id = :link)
                 ORDER BY created_at""")
                .param("order", shopOrderId).param("link", payLinkId).query(UUID.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Open> allOpen() {
        return jdbc.sql(OPEN + " AND created_at > now() - interval '7 days' ORDER BY created_at LIMIT 50")
                .query(JdbcPaymentAttempts::openRow).list();
    }

    /**
     * Linha de tentativa em análise.
     *
     * @param rs  linha
     * @param row número da linha
     * @return a tentativa
     * @throws SQLException se a leitura falhar
     */
    private static Open openRow(ResultSet rs, int row) throws SQLException {
        return new Open(rs.getObject(1, UUID.class), rs.getObject(2) == null ? null : rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4));
    }
}
