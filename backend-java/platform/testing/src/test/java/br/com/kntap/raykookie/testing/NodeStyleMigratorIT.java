package br.com.kntap.raykookie.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Prova que {@link NodeStyleMigrator} aplica as migrations como o {@code backend/scripts/migrate.mjs}:
 * tabela {@code _migrations}, ordem do nome original, uma transação por arquivo e nada repetido.
 */
class NodeStyleMigratorIT extends PostgresTestBase {

    /** Consulta dos nomes gravados pelo migrador, na ordem de aplicação. */
    private static final String APPLIED = "SELECT name FROM _migrations ORDER BY applied_at, name";

    /**
     * Os nomes das cópias {@code V<N>__<original>} voltam a ser o nome original, ordenados como no Node.
     */
    @Test
    @DisplayName("lê as cópias do classpath com o nome original do Node, em ordem")
    void readsOriginalNames() {
        NodeStyleMigrator migrator = NodeStyleMigrator.fromClasspath("node-style/ok");

        assertThat(migrator.fileNames()).containsExactly("001_alpha.sql", "002_beta.sql", "010_gamma.sql");
    }

    /**
     * Aplica tudo, grava os nomes originais e, numa segunda rodada, não reaplica nada.
     */
    @Test
    @DisplayName("aplica todos os arquivos uma única vez e grava o nome original")
    void appliesEveryFileOnce() {

        // ── 1. Banco vazio; aplica tudo e depois de novo.
        TestDatabase database = newDatabase("node_ok");
        NodeStyleMigrator migrator = NodeStyleMigrator.fromClasspath("node-style/ok");
        List<String> first = migrator.migrate(database.dataSource());
        List<String> second = migrator.migrate(database.dataSource());

        // ── 2. A primeira aplicou os três; a segunda, nenhum; o bloco DO $$ rodou uma vez só.
        JdbcClient jdbc = database.jdbc();
        assertThat(first).containsExactly("001_alpha.sql", "002_beta.sql", "010_gamma.sql");
        assertThat(second).isEmpty();
        assertThat(jdbc.sql(APPLIED).query(String.class).list()).containsExactlyElementsOf(first);
        assertThat(jdbc.sql("SELECT count(*) FROM alpha").query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * Com limite, aplica só os primeiros arquivos (simula um banco que parou no meio).
     */
    @Test
    @DisplayName("com limite, aplica só os primeiros arquivos")
    void appliesOnlyTheFirstFiles() {
        TestDatabase database = newDatabase("node_limit");

        List<String> applied = NodeStyleMigrator.fromClasspath("node-style/ok").migrate(database.dataSource(), 2);

        assertThat(applied).containsExactly("001_alpha.sql", "002_beta.sql");
        assertThat(database.jdbc().sql(APPLIED).query(String.class).list()).containsExactlyElementsOf(applied);
    }

    /**
     * Arquivo com erro: ele inteiro é desfeito (inclusive o que rodou antes do erro) e o anterior fica.
     */
    @Test
    @DisplayName("arquivo com erro é desfeito por inteiro e interrompe a aplicação")
    void rollsBackTheFailingFile() {

        // ── 1. O segundo arquivo cria uma tabela e depois falha.
        TestDatabase database = newDatabase("node_broken");
        NodeStyleMigrator migrator = NodeStyleMigrator.fromClasspath("node-style/broken");

        // ── 2. A falha cita o arquivo.
        assertThatThrownBy(() -> migrator.migrate(database.dataSource()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("002_beta.sql");

        // ── 3. Só o primeiro ficou gravado; a tabela do segundo não existe.
        JdbcClient jdbc = database.jdbc();
        assertThat(jdbc.sql(APPLIED).query(String.class).list()).containsExactly("001_alpha.sql");
        assertThat(jdbc.sql("SELECT to_regclass('beta') IS NULL").query(Boolean.class).single()).isTrue();
    }

    /**
     * Pasta sem nenhuma migration é erro de configuração do teste, não um banco vazio silencioso.
     */
    @Test
    @DisplayName("pasta sem migrations é recusada")
    void rejectsEmptyFolder() {
        assertThatThrownBy(() -> NodeStyleMigrator.fromClasspath("node-style/nada"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("node-style/nada");
    }
}
