package br.com.kntap.raykookie.storefront.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.storefront.api.PickupAddress;
import br.com.kntap.raykookie.storefront.application.ports.PickupAddresses;
import br.com.kntap.raykookie.storefront.domain.PickupForm;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Endereços de retirada com o mesmo SQL de {@code backend/lib/pickup.ts} e das rotas {@code /api/pickup-addresses}.
 */
@Repository
public class JdbcPickupAddresses implements PickupAddresses {

    /** Colunas lidas. */
    private static final String COLS =
            "id, label, cep, street, number, complement, district, city, instructions, is_active";

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcPickupAddresses(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Uma linha como endereço.
     *
     * @param rs  resultado
     * @param row número da linha
     * @return o endereço
     * @throws SQLException se a leitura falhar
     */
    private static PickupAddress map(ResultSet rs, int row) throws SQLException {
        return new PickupAddress(rs.getLong("id"), rs.getString("label"), rs.getString("cep"), rs.getString("street"),
                rs.getString("number"), rs.getString("complement"), rs.getString("district"), rs.getString("city"),
                rs.getString("instructions"), rs.getBoolean("is_active"));
    }

    /** {@inheritDoc} */
    @Override
    public List<PickupAddress> list(OwnerId owner) {
        return jdbc.sql("SELECT " + COLS + " FROM pickup_addresses WHERE user_id = :owner "
                + "ORDER BY is_active DESC, lower(label), id").param("owner", owner.value())
                .query(JdbcPickupAddresses::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PickupAddress> active(OwnerId owner) {
        return jdbc.sql("SELECT " + COLS + " FROM pickup_addresses WHERE user_id = :owner AND is_active")
                .param("owner", owner.value()).query(JdbcPickupAddresses::map).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PickupAddress> find(OwnerId owner, long id) {
        return jdbc.sql("SELECT " + COLS + " FROM pickup_addresses WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).query(JdbcPickupAddresses::map).optional();
    }

    /** {@inheritDoc} */
    @Override
    public int count(OwnerId owner) {
        return jdbc.sql("SELECT count(*)::int FROM pickup_addresses WHERE user_id = :owner")
                .param("owner", owner.value()).query(Integer.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public void deactivateOthers(OwnerId owner, long except) {
        jdbc.sql("UPDATE pickup_addresses SET is_active = false WHERE user_id = :owner AND is_active AND id <> :id")
                .param("owner", owner.value()).param("id", except).update();
    }

    /** {@inheritDoc} */
    @Override
    public PickupAddress create(OwnerId owner, PickupForm form, boolean active) {
        return jdbc.sql("""
                INSERT INTO pickup_addresses (user_id, label, cep, street, number, complement, district, city,
                                              instructions, is_active)
                VALUES (:owner, :label, :cep, :street, :number, :complement, :district, :city, :instructions, :active)
                RETURNING""" + " " + COLS)
                .param("owner", owner.value()).param("label", form.label()).param("cep", form.cep())
                .param("street", form.street()).param("number", form.number()).param("complement", form.complement())
                .param("district", form.district()).param("city", form.city())
                .param("instructions", form.instructions()).param("active", active)
                .query(JdbcPickupAddresses::map).single();
    }

    /** {@inheritDoc} */
    @Override
    public void activate(OwnerId owner, long id) {
        jdbc.sql("UPDATE pickup_addresses SET is_active = true WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }

    /** {@inheritDoc} */
    @Override
    public void update(OwnerId owner, long id, PickupForm form) {
        jdbc.sql("""
                UPDATE pickup_addresses SET label = :label, cep = :cep, street = :street, number = :number,
                       complement = :complement, district = :district, city = :city, instructions = :instructions
                 WHERE id = :id AND user_id = :owner""")
                .param("id", id).param("owner", owner.value()).param("label", form.label()).param("cep", form.cep())
                .param("street", form.street()).param("number", form.number()).param("complement", form.complement())
                .param("district", form.district()).param("city", form.city())
                .param("instructions", form.instructions()).update();
    }

    /** {@inheritDoc} */
    @Override
    public void delete(OwnerId owner, long id) {
        jdbc.sql("DELETE FROM pickup_addresses WHERE id = :id AND user_id = :owner")
                .param("id", id).param("owner", owner.value()).update();
    }
}
