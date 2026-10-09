package br.com.kntap.raykookie.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reproduz, em Java, o {@code backend/scripts/migrate.mjs} do backend Node, para montar nos testes um
 * banco exatamente como o de produção nasceu: cria {@code _migrations(name, applied_at)}, percorre os
 * arquivos em ordem do nome original ({@code 001_init.sql}, {@code 002_shop.sql}…), pula os já
 * gravados e aplica cada um numa transação própria (o arquivo inteiro de uma vez, mais o
 * {@code INSERT} do nome); se um arquivo falha, ele é desfeito por inteiro e nada depois dele roda.
 *
 * <p>Os arquivos lidos são as cópias byte a byte dentro de backend-java ({@code V<N>__<original>.sql}
 * em {@value #PRODUCTION_COPIES}); o nome gravado é o original, depois do {@code __}.
 */
public final class NodeStyleMigrator {

    /** Pasta do classpath com as cópias das 21 migrations de produção. */
    public static final String PRODUCTION_COPIES = "db/migration/production";

    /** Mesma tabela criada pelo migrador do Node. */
    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS _migrations "
            + "(name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())";

    /**
     * Uma migration: o nome original do Node e o SQL do arquivo.
     *
     * @param name nome original, por exemplo {@code 001_init.sql}
     * @param sql  conteúdo do arquivo, lido como UTF-8
     */
    private record Migration(String name, String sql) {
    }

    /** Migrations em ordem do nome original (a mesma do {@code .sort()} do Node). */
    private final List<Migration> migrations;

    /**
     * Guarda as migrations já ordenadas.
     *
     * @param migrations migrations lidas do classpath
     */
    private NodeStyleMigrator(List<Migration> migrations) {
        this.migrations = migrations;
    }

    /**
     * Lê as cópias {@code V<N>__<original>.sql} de uma pasta do classpath.
     *
     * @param folder pasta do classpath, por exemplo {@value #PRODUCTION_COPIES}
     * @return o migrador com as migrations da pasta
     * @throws IllegalStateException se a pasta não tiver nenhuma migration
     */
    public static NodeStyleMigrator fromClasspath(String folder) {

        // ── 1. Acha os arquivos da pasta.
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources("classpath*:" + folder + "/V*__*.sql");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("Nenhuma migration em classpath:" + folder);
        }

        // ── 2. Volta ao nome original e ordena como o Node ordena.
        List<Migration> migrations = new ArrayList<>();
        for (Resource resource : resources) {
            String copy = resource.getFilename();
            migrations.add(new Migration(copy.substring(copy.indexOf("__") + 2), read(resource)));
        }
        migrations.sort(Comparator.comparing(Migration::name));
        return new NodeStyleMigrator(List.copyOf(migrations));
    }

    /**
     * Nomes originais das migrations, na ordem em que são aplicadas.
     *
     * @return os nomes, por exemplo {@code [001_init.sql, 002_shop.sql, …]}
     */
    public List<String> fileNames() {
        return migrations.stream().map(Migration::name).toList();
    }

    /**
     * Aplica todas as migrations ainda não gravadas em {@code _migrations}.
     *
     * @param dataSource banco de destino
     * @return os nomes aplicados nesta chamada, em ordem
     */
    public List<String> migrate(DataSource dataSource) {
        return migrate(dataSource, migrations.size());
    }

    /**
     * Aplica só as primeiras {@code count} migrations (como se o Node conhecesse apenas essas).
     *
     * @param dataSource banco de destino
     * @param count      quantas migrations, a partir da primeira, considerar
     * @return os nomes aplicados nesta chamada, em ordem
     * @throws IllegalStateException se uma migration falhar (ela é desfeita e as seguintes não rodam)
     */
    public List<String> migrate(DataSource dataSource, int count) {
        try (Connection connection = dataSource.getConnection()) {

            // ── 1. Cria a tabela de controle e lê o que já foi aplicado.
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
            }
            Set<String> done = appliedNames(connection);

            // ── 2. Aplica, em ordem, cada arquivo que falta, um por transação.
            List<String> applied = new ArrayList<>();
            for (Migration migration : migrations.subList(0, count)) {
                if (!done.contains(migration.name())) {
                    apply(connection, migration);
                    applied.add(migration.name());
                }
            }
            return applied;
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao abrir o banco das migrations: " + e.getMessage(), e);
        }
    }

    /**
     * Lê os nomes já gravados em {@code _migrations}.
     *
     * @param connection conexão aberta
     * @return os nomes aplicados
     * @throws SQLException se a consulta falhar
     */
    private static Set<String> appliedNames(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT name FROM _migrations")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    /**
     * Aplica um arquivo e grava o nome dele numa única transação; em erro, desfaz tudo.
     *
     * @param connection conexão aberta
     * @param migration  migration a aplicar
     * @throws SQLException se o próprio desfazer falhar
     */
    private static void apply(Connection connection, Migration migration) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO _migrations(name) VALUES (?)")) {
            statement.execute(migration.sql());
            insert.setString(1, migration.name());
            insert.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new IllegalStateException("falhou: " + migration.name() + " " + e.getMessage(), e);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Lê o arquivo inteiro como UTF-8 (como o {@code readFileSync(…, 'utf8')} do Node).
     *
     * @param resource arquivo do classpath
     * @return o conteúdo
     */
    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
