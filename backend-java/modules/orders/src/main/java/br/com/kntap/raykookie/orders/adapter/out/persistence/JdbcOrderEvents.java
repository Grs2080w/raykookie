package br.com.kntap.raykookie.orders.adapter.out.persistence;

import br.com.kntap.raykookie.orders.application.ports.OrderEvents;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Histórico em {@code order_events} (na transação de quem chama, quando há uma).
 */
@Repository
public class JdbcOrderEvents implements OrderEvents {

    /** JSON dos detalhes. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcOrderEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void log(long orderId, String actor, String type, Map<String, Object> data) {
        jdbc.sql("INSERT INTO order_events (shop_order_id, actor, type, data) VALUES (:order, :actor, :type, "
                + "CAST(:data AS jsonb))").param("order", orderId).param("actor", actor).param("type", type)
                .param("data", JSON.writeValueAsString(data)).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> of(long orderId) {
        return jdbc.sql("SELECT at, actor, type, data FROM order_events WHERE shop_order_id = :order ORDER BY at, id")
                .param("order", orderId).query(NodeRows.ROW).list();
    }
}
