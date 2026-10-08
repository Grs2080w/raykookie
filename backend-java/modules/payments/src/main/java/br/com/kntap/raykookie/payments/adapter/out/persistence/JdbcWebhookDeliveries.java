package br.com.kntap.raykookie.payments.adapter.out.persistence;

import br.com.kntap.raykookie.payments.application.ports.WebhookDeliveries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Entregas de webhook já processadas ({@code webhook_deliveries}).
 */
@Repository
public class JdbcWebhookDeliveries implements WebhookDeliveries {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcWebhookDeliveries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public boolean seen(String requestId) {
        return jdbc.sql("SELECT 1 FROM webhook_deliveries WHERE request_id = :id").param("id", requestId)
                .query(Integer.class).optional().isPresent();
    }

    /** {@inheritDoc} */
    @Override
    public void remember(String requestId, String dataId) {
        jdbc.sql("INSERT INTO webhook_deliveries (request_id, data_id) VALUES (:id, :data) ON CONFLICT DO NOTHING")
                .param("id", requestId).param("data", dataId).update();
    }

    /** {@inheritDoc} */
    @Override
    public int purgeOlderThanDays(int days) {
        return jdbc.sql("DELETE FROM webhook_deliveries WHERE received_at < now() - make_interval(days => :days)")
                .param("days", days).update();
    }
}
