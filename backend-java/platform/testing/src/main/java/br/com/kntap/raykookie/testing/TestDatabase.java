package br.com.kntap.raykookie.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
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
     * <p>O driver JDBC manda o fuso da JVM como fuso da sessão; o Node não manda nenhum, e a sessão fica
     * no fuso do servidor ({@value PostgresTestBase#ZONE}). Fixar o fuso da sessão aqui deixa estas
     * conexões iguais às do Node, seja qual for o fuso da máquina que roda os testes.
     *
     * @param jdbcUrl  URL JDBC
     * @param username usuário
     * @param password senha
     * @return a fonte de conexões
     */
    static DataSource dataSourceFor(String jdbcUrl, String username, String password) {
        return new StoreZoneDataSource(new SimpleDriverDataSource(new Driver(), jdbcUrl, username, password));
    }

    /**
     * Fonte de conexões que põe cada conexão nova no fuso da loja antes de entregá-la. Não dá para usar o
     * parâmetro {@code options} do driver: o {@code TimeZone} que ele manda na abertura vence o {@code options}.
     */
    private static final class StoreZoneDataSource extends DelegatingDataSource {

        /**
         * Envolve a fonte de conexões diretas.
         *
         * @param target a fonte que abre as conexões
         */
        StoreZoneDataSource(DataSource target) {
            super(target);
        }

        /**
         * Abre uma conexão e fixa o fuso da sessão.
         *
         * @return a conexão no fuso da loja
         * @throws SQLException se a conexão não abrir ou o fuso não puder ser fixado
         */
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE '" + PostgresTestBase.ZONE + "'");
            }
            return connection;
        }
    }
}
