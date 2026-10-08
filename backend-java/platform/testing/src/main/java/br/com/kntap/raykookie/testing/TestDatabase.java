package br.com.kntap.raykookie.testing;

import javax.sql.DataSource;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Um banco criado por {@link PostgresTestBase#newDatabase(String)} no container compartilhado.
 *
 * @param name     nome do banco
 * @param jdbcUrl  URL JDBC do banco
 * @param username usuário
 * @param password senha
 */
public record TestDatabase(String name, String jdbcUrl, String username, String password) {

    /**
     * Conexões simples (sem pool) com este banco.
     *
     * @return uma fonte de conexões
     */
    public DataSource dataSource() {
        return dataSourceFor(jdbcUrl, username, password);
    }

    /**
     * Cliente JDBC sobre este banco.
     *
     * @return o cliente
     */
    public JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /**
     * Fonte de conexões sem pool: cada pedido abre uma conexão nova pelo driver do Postgres.
     *
     * @param jdbcUrl  URL JDBC
     * @param username usuário
     * @param password senha
     * @return a fonte de conexões
     */
    static DataSource dataSourceFor(String jdbcUrl, String username, String password) {
        return new SimpleDriverDataSource(new Driver(), jdbcUrl, username, password);
    }
}
