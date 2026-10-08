package br.com.kntap.raykookie.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova que a base dos testes com banco entrega um Postgres igual ao de produção (versão e fuso),
 * compartilhado pela JVM inteira, e bancos vazios isolados para os testes de migration.
 */
class PostgresTestBaseIT extends PostgresTestBase {

    /**
     * A imagem é a mesma versão de produção e o servidor roda no fuso da loja.
     */
    @Test
    @DisplayName("o container é o postgres 17.11 no fuso America/Sao_Paulo")
    void containerMatchesProduction() {

        // ── 1. Pergunta ao próprio servidor a versão e o fuso.
        String version = jdbc().sql("SHOW server_version").query(String.class).single();
        String zone = jdbc().sql("SHOW timezone").query(String.class).single();

        // ── 2. Iguais aos de produção.
        assertThat(version).startsWith("17.11");
        assertThat(zone).isEqualTo("America/Sao_Paulo");
    }

    /**
     * O container é um só para a JVM: duas chamadas devolvem o mesmo objeto, já rodando.
     */
    @Test
    @DisplayName("o container é compartilhado e já está de pé")
    void containerIsShared() {
        assertThat(postgres()).isSameAs(postgres());
        assertThat(postgres().isRunning()).isTrue();
        assertThat(jdbcUrl()).isEqualTo(postgres().getJdbcUrl());
    }

    /**
     * As propriedades dinâmicas apontam o Spring para o banco do container.
     */
    @Test
    @DisplayName("as propriedades rayka.db.* apontam para o container")
    void registersDatabaseProperties() {

        // ── 1. Coleta o que a base registra.
        Map<String, Supplier<Object>> registered = new HashMap<>();
        PostgresTestBase.databaseProperties(registered::put);

        // ── 2. URL, usuário e senha do container.
        assertThat(registered.get("rayka.db.url").get()).isEqualTo(postgres().getJdbcUrl());
        assertThat(registered.get("rayka.db.username").get()).isEqualTo(postgres().getUsername());
        assertThat(registered.get("rayka.db.password").get()).isEqualTo(postgres().getPassword());
    }

    /**
     * Cada banco novo nasce vazio, com nome próprio, no mesmo servidor.
     */
    @Test
    @DisplayName("newDatabase cria um banco vazio e separado")
    void createsEmptyDatabases() {

        // ── 1. Cria dois bancos com o mesmo prefixo e escreve só no primeiro.
        TestDatabase first = newDatabase("vazio");
        TestDatabase second = newDatabase("vazio");
        first.jdbc().sql("CREATE TABLE marker (id INTEGER)").update();

        // ── 2. Nomes diferentes, URLs do mesmo servidor e o segundo continua sem tabelas.
        assertThat(first.name()).isNotEqualTo(second.name()).startsWith("vazio_");
        assertThat(first.jdbcUrl()).endsWith("/" + first.name());
        assertThat(first.username()).isEqualTo(postgres().getUsername());
        assertThat(first.password()).isEqualTo(postgres().getPassword());
        String tables = "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'";
        assertThat(second.jdbc().sql(tables).query(Integer.class).single()).isZero();
        assertThat(first.jdbc().sql(tables).query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * O nome vai direto no SQL, então só aceita prefixos seguros.
     */
    @Test
    @DisplayName("newDatabase recusa prefixo que não é um identificador simples")
    void rejectsUnsafePrefix() {
        assertThatThrownBy(() -> newDatabase("x; DROP DATABASE test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prefixo de banco inválido");
    }
}
