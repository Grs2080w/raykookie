package br.com.kntap.raykookie.orders.adapter.out.persistence;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ports.OrderNumbers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Numeração com trava por dona no Postgres. A trava usa a forma de duas chaves ({@code 1001, dona}), que não colide
 * com as travas de uma chave das tarefas agendadas.
 */
@Repository
public class JdbcOrderNumbers implements OrderNumbers {

    /** Espaço de chaves das travas de numeração. */
    private static final int LOCK_CLASS = 1001;

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcOrderNumbers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void lock(OwnerId owner) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:class, :owner)::text").param("class", LOCK_CLASS)
                .param("owner", (int) owner.value()).query(String.class).single();
    }

    /** {@inheritDoc} */
    @Override
    public int next(OwnerId owner) {
        return jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM orders WHERE user_id = :owner")
                .param("owner", owner.value()).query(Integer.class).single();
    }
}
