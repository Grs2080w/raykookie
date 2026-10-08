package br.com.kntap.raykookie.testing;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de todo teste que usa banco: um Postgres igual ao de produção ({@value #IMAGE}, fuso
 * {@value #ZONE}) num container do Testcontainers, iniciado uma vez e compartilhado pela JVM inteira.
 *
 * <p>Testes {@code @SpringBootTest} que estendem esta classe recebem {@code rayka.db.url},
 * {@code rayka.db.username} e {@code rayka.db.password} do container; o Flyway da aplicação cria o
 * esquema no banco principal (modo "banco novo"). Testes de migration usam {@link #newDatabase(String)}
 * para ganhar um banco vazio no mesmo servidor. O container não é anotado com {@code @Container}
 * (que o pararia ao fim de cada classe) nem reaproveitado entre execuções ({@code withReuse}): cada
 * execução da suíte começa num servidor limpo, e o Ryuk o remove quando a JVM termina.
 */
public abstract class PostgresTestBase {

    /** Mesma imagem do Postgres de produção. */
    public static final String IMAGE = "postgres:17.11";

    /** Fuso da loja, igual ao do servidor de produção. */
    public static final String ZONE = "America/Sao_Paulo";

    /** O container compartilhado, iniciado na primeira vez que a classe é carregada. */
    private static final PostgreSQLContainer POSTGRES = start();

    /** Contador que deixa único o nome de cada banco criado por {@link #newDatabase(String)}. */
    private static final AtomicInteger DATABASES = new AtomicInteger();

    /**
     * Cria e inicia o container no fuso da loja.
     *
     * @return o container já rodando
     */
    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withEnv("TZ", ZONE)
                .withEnv("PGTZ", ZONE)
                .withCommand("postgres", "-c", "timezone=" + ZONE);
        container.start();
        return container;
    }

    /**
     * Aponta a configuração do Spring para o banco principal do container.
     *
     * @param registry registro de propriedades dinâmicas do teste
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("rayka.db.url", POSTGRES::getJdbcUrl);
        registry.add("rayka.db.username", POSTGRES::getUsername);
        registry.add("rayka.db.password", POSTGRES::getPassword);
    }

    /**
     * O container compartilhado (por exemplo, para pausá-lo num teste de tempo limite).
     *
     * @return o container rodando
     */
    protected static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    /**
     * URL JDBC do banco principal do container.
     *
     * @return a URL, no formato {@code jdbc:postgresql://host:porta/banco}
     */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /**
     * Conexões simples (sem pool) com o banco principal, para preparar e conferir dados.
     *
     * @return uma fonte de conexões com o banco principal
     */
    protected static DataSource dataSource() {
        return TestDatabase.dataSourceFor(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Cliente JDBC sobre o banco principal do container.
     *
     * @return o cliente
     */
    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /**
     * Cria um banco vazio no mesmo servidor (mesma versão, mesmo fuso), com nome único.
     *
     * @param prefix começo do nome (só letras minúsculas, dígitos e {@code _})
     * @return o banco recém-criado
     */
    protected static TestDatabase newDatabase(String prefix) {

        // ── 1. Nome único e seguro para usar direto no SQL.
        if (!prefix.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Prefixo de banco inválido: " + prefix);
        }
        String name = prefix + "_" + DATABASES.incrementAndGet();

        // ── 2. Cria o banco a partir do principal e devolve as coordenadas dele.
        JdbcClient.create(dataSource()).sql("CREATE DATABASE " + name).update();
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return new TestDatabase(name, url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
