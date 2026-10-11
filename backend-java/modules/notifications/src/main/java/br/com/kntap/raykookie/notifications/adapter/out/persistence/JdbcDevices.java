package br.com.kntap.raykookie.notifications.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.application.ports.Devices;
import br.com.kntap.raykookie.notifications.domain.OrderSound;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Aparelhos nas tabelas do Node, com o mesmo SQL das rotas {@code /api/devices} e {@code /api/cliente/devices}.
 */
@Repository
public class JdbcDevices implements Devices {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public List<OwnerDevice> ofOwner(OwnerId owner) {
        return jdbc.sql("SELECT token, sound FROM device_tokens WHERE user_id = :owner ORDER BY id")
                .param("owner", owner.value())
                .query((rs, row) -> new OwnerDevice(rs.getString("token"), rs.getString("sound")))
                .list();
    }

    /** {@inheritDoc} */
    @Override
    public List<OwnerDevice> allOwners() {
        return jdbc.sql("SELECT token, sound FROM device_tokens ORDER BY id")
                .query((rs, row) -> new OwnerDevice(rs.getString(1), rs.getString(2))).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> allCustomers() {
        return jdbc.sql("SELECT token FROM customer_devices ORDER BY id").query(String.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> ofCustomer(CustomerId customer) {
        return jdbc.sql("SELECT token FROM customer_devices WHERE customer_id = :customer ORDER BY id")
                .param("customer", customer.value()).query(String.class).list();
    }

    /** {@inheritDoc} */
    @Override
    public void forgetOwnerTokens(List<String> tokens) {
        jdbc.sql("DELETE FROM device_tokens WHERE token IN (:tokens)").param("tokens", tokens).update();
    }

    /** {@inheritDoc} */
    @Override
    public void forgetCustomerTokens(List<String> tokens) {
        jdbc.sql("DELETE FROM customer_devices WHERE token IN (:tokens)").param("tokens", tokens).update();
    }

    /** {@inheritDoc} */
    @Override
    public void registerOwner(OwnerId owner, String token, String sound) {
        jdbc.sql("""
                INSERT INTO device_tokens (user_id, token, platform, sound) VALUES (:owner, :token, 'android', :initial)
                ON CONFLICT (token) DO UPDATE SET user_id = :owner, sound = COALESCE(:sound, device_tokens.sound),
                                                  last_seen = now()""")
                .param("owner", owner.value()).param("token", token)
                .param("initial", sound == null ? OrderSound.DEFAULT : sound).param("sound", sound)
                .update();
    }

    /** {@inheritDoc} */
    @Override
    public void unregisterOwner(OwnerId owner, String token) {
        jdbc.sql("DELETE FROM device_tokens WHERE token = :token AND user_id = :owner")
                .param("token", token).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public void registerCustomer(CustomerId customer, String token) {
        jdbc.sql("""
                INSERT INTO customer_devices (customer_id, token) VALUES (:customer, :token)
                ON CONFLICT (token) DO UPDATE SET customer_id = :customer, last_seen = now()""")
                .param("customer", customer.value()).param("token", token).update();
    }

    /** {@inheritDoc} */
    @Override
    public void unregisterCustomer(CustomerId customer, String token) {
        jdbc.sql("DELETE FROM customer_devices WHERE token = :token AND customer_id = :customer")
                .param("token", token).param("customer", customer.value()).update();
    }
}
