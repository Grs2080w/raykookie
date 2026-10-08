package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.testing.NodeStyleMigrator;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import br.com.kntap.raykookie.testing.TestDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Prova os dois modos do Flyway (banco novo e banco de produção com baseline 21), a fidelidade das
 * cópias das migrations do Node, a guarda do esquema de produção e a política só-aditiva das V22+.
 */
class MigrationsIT extends PostgresTestBase {

    /** Pasta das cópias byte a byte das migrations do Node. */
    private static final String PRODUCTION = "db/migration/production";

    /** Pasta das migrations da plataforma Java (V22 em diante). */
    private static final String PLATFORM = "db/migration/platform";

    /** Tabelas de controle das migrations, que existem só num dos caminhos ou mudam de conteúdo. */
    private static final String HISTORY = "('_migrations', 'flyway_schema_history')";

    /** Palavras que indicam mudança destrutiva, proibidas nas migrations V22+ (G29). */
    private static final List<String> FORBIDDEN = List.of("DROP ", "RENAME ", "ALTER COLUMN", "TYPE ");

    /**
     * Cada cópia {@code V<N>__<original>.sql} tem o sha256 do original gravado em SHA256SUMS, e não
     * há cópia sem original nem original sem cópia.
     *
     * @throws IOException se algum arquivo não puder ser lido
     */
    @Test
    @DisplayName("as cópias das migrations de produção batem com o SHA256SUMS dos originais")
    void productionMigrationsMatchOriginals() throws IOException {

        // ── 1. Lê o SHA256SUMS (linhas "<hash>  <nome original>").
        String sums = new ClassPathResource(PRODUCTION + "/SHA256SUMS").getContentAsString(StandardCharsets.UTF_8);
        List<String[]> lines = sums.lines().map(line -> line.split("  ", 2)).toList();

        // ── 2. Cada original listado tem cópia com o mesmo hash.
        List<String> expectedCopies = new ArrayList<>();
        for (String[] line : lines) {
            String copy = "V" + Integer.parseInt(line[1].substring(0, 3)) + "__" + line[1];
            byte[] content = new ClassPathResource(PRODUCTION + "/" + copy).getContentAsByteArray();
            assertThat(sha256(content)).as(copy).isEqualTo(line[0]);
            expectedCopies.add(copy);
        }

        // ── 3. Não sobra cópia fora da lista, e são as 21 migrations do Node.
        List<String> copies = Arrays.stream(resources(PRODUCTION)).map(Resource::getFilename).toList();
        assertThat(copies).containsExactlyInAnyOrderElementsOf(expectedCopies);
        assertThat(lines).hasSize(21);
        assertThat(lines.getLast()[1]).isEqualTo("021_card_payment.sql");
    }

    /**
     * Um banco montado como o Node monta (o de produção) aceita o modo produção: baseline 21, só a
     * V22+ aplicada, validação sem erro e a guarda satisfeita; a {@code _migrations} fica intacta.
     */
    @Test
    @DisplayName("banco montado pelo migrador do Node aceita o baseline 21 e recebe só as V22+")
    void nodeStyleDatabaseAcceptsBaseline() {

        // ── 1. Monta o banco como em produção.
        TestDatabase database = newDatabase("producao");
        NodeStyleMigrator.fromClasspath(NodeStyleMigrator.PRODUCTION_COPIES).migrate(database.dataSource());

        // ── 2. Sobe no perfil prod (guarda + Flyway com baseline) e confere o histórico do Flyway.
        try (ConfigurableApplicationContext context = boot(database, "prod")) {
            Flyway flyway = context.getBean(Flyway.class);
            flyway.validate();
            List<String> versions = Arrays.stream(flyway.info().applied())
                    .map(info -> info.getVersion().getVersion() + ":" + info.getType())
                    .toList();
            assertThat(versions).containsExactly("21:BASELINE", "22:SQL");
        }

        // ── 3. As tabelas da plataforma existem e o controle do Node não foi tocado.
        JdbcClient jdbc = database.jdbc();
        assertThat(jdbc.sql("SELECT to_regclass('payment_attempts') IS NOT NULL").query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM _migrations").query(Integer.class).single()).isEqualTo(21);
    }

    /**
     * O banco novo (Flyway aplicando 1→22) e o de produção (Node 001→021 + baseline + V22) ficam com o
     * mesmo esquema: colunas, índices e restrições.
     */
    @Test
    @DisplayName("os dois caminhos produzem exatamente o mesmo esquema")
    void bothPathsProduceSameSchema() {

        // ── 1. Caminho "banco novo": o Flyway aplica tudo.
        TestDatabase fresh = newDatabase("novo");
        try (ConfigurableApplicationContext context = boot(fresh)) {
            List<MigrationInfo> applied = Arrays.asList(context.getBean(Flyway.class).info().applied());
            assertThat(applied).hasSize(22);
        }

        // ── 2. Caminho "produção": migrador do Node e depois o Flyway com baseline.
        TestDatabase production = newDatabase("legado");
        NodeStyleMigrator.fromClasspath(NodeStyleMigrator.PRODUCTION_COPIES).migrate(production.dataSource());
        boot(production, "prod").close();

        // ── 3. Compara colunas, índices e restrições.
        assertThat(schema(production.jdbc())).isEqualTo(schema(fresh.jdbc()));
        assertThat(schema(fresh.jdbc()).get(0)).isNotEmpty();
    }

    /**
     * Banco de produção com só 20 das 21 migrations: o servidor não sobe e a mensagem cita a que falta;
     * o Flyway nem chega a rodar.
     */
    @Test
    @DisplayName("a guarda recusa banco de produção sem a migration 021")
    void guardRejectsUnknownState() {

        // ── 1. Banco parado na 020.
        TestDatabase database = newDatabase("incompleto");
        NodeStyleMigrator.fromClasspath(NodeStyleMigrator.PRODUCTION_COPIES).migrate(database.dataSource(), 20);

        // ── 2. A subida falha citando o arquivo que falta.
        assertThatThrownBy(() -> boot(database, "prod"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("021_card_payment.sql");

        // ── 3. Nada do Flyway foi criado.
        JdbcClient jdbc = database.jdbc();
        assertThat(jdbc.sql("SELECT to_regclass('flyway_schema_history') IS NULL").query(Boolean.class).single())
                .isTrue();
    }

    /**
     * Banco de produção com uma migration a mais (que os testes não conhecem): o servidor não sobe.
     */
    @Test
    @DisplayName("a guarda recusa banco de produção com migration desconhecida")
    void guardRejectsExtraMigration() {

        // ── 1. Banco completo mais um registro estranho.
        TestDatabase database = newDatabase("excedente");
        NodeStyleMigrator.fromClasspath(NodeStyleMigrator.PRODUCTION_COPIES).migrate(database.dataSource());
        database.jdbc().sql("INSERT INTO _migrations(name) VALUES ('022_desconhecida.sql')").update();

        // ── 2. A subida falha citando o excedente.
        assertThatThrownBy(() -> boot(database, "prod"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("022_desconhecida.sql");
    }

    /**
     * Banco sem a tabela {@code _migrations} do Node não é o de produção: o servidor não sobe.
     */
    @Test
    @DisplayName("a guarda recusa banco sem a tabela _migrations")
    void guardRejectsDatabaseWithoutNodeHistory() {
        TestDatabase database = newDatabase("sem_controle");

        assertThatThrownBy(() -> boot(database, "prod"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_migrations");
    }

    /**
     * As migrations da plataforma (V22+) só acrescentam: sem DROP, RENAME, ALTER COLUMN nem TYPE (G29).
     *
     * @throws IOException se algum arquivo não puder ser lido
     */
    @Test
    @DisplayName("migrations V22+ são só aditivas")
    void migrationsAreAdditiveOnly() throws IOException {

        // ── 1. Junta as migrations da plataforma com versão 22 ou maior.
        List<Resource> checked = new ArrayList<>();
        for (Resource resource : resources(PLATFORM)) {
            String name = resource.getFilename();
            if (Integer.parseInt(name.substring(1, name.indexOf("__")).split("\\.")[0]) >= 22) {
                checked.add(resource);
            }
        }

        // ── 2. Nenhuma contém palavra destrutiva fora de comentário.
        for (Resource resource : checked) {
            String sql = resource.getContentAsString(StandardCharsets.UTF_8)
                    .replaceAll("--[^\\n]*", "")
                    .toUpperCase(Locale.ROOT);
            assertThat(FORBIDDEN).as(resource.getFilename()).noneMatch(sql::contains);
        }
        assertThat(checked).isNotEmpty();
    }

    /**
     * Sobe a persistência (sem web) apontada para um banco de teste.
     *
     * @param database banco de destino
     * @param profiles perfis ativos (por exemplo {@code prod})
     * @return o contexto rodando; quem chama fecha
     */
    private static ConfigurableApplicationContext boot(TestDatabase database, String... profiles) {
        return new SpringApplicationBuilder(PersistenceTestApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .properties("rayka.db.url=" + database.jdbcUrl(),
                        "rayka.db.username=" + database.username(),
                        "rayka.db.password=" + database.password())
                .run();
    }

    /**
     * Fotografia do esquema público, sem as tabelas de controle das migrations.
     *
     * @param jdbc cliente do banco
     * @return colunas, índices e restrições, nessa ordem, cada lista ordenada
     */
    private static List<List<Map<String, Object>>> schema(JdbcClient jdbc) {
        String columns = "SELECT table_name, column_name, ordinal_position, data_type, is_nullable, column_default,"
                + " character_maximum_length, numeric_precision, numeric_scale FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name NOT IN " + HISTORY + " ORDER BY 1, 2";
        String indexes = "SELECT tablename, indexname, indexdef FROM pg_indexes"
                + " WHERE schemaname = 'public' AND tablename NOT IN " + HISTORY + " ORDER BY 1, 2";
        String constraints = "SELECT conname, conrelid::regclass::text AS table_name,"
                + " pg_get_constraintdef(oid) AS definition FROM pg_constraint"
                + " WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text NOT IN " + HISTORY
                + " ORDER BY 2, 1, 3";
        return List.of(jdbc.sql(columns).query().listOfRows(),
                jdbc.sql(indexes).query().listOfRows(),
                jdbc.sql(constraints).query().listOfRows());
    }

    /**
     * Arquivos {@code V*__*.sql} de uma pasta do classpath.
     *
     * @param folder pasta do classpath
     * @return os arquivos encontrados
     * @throws IOException se a pasta não puder ser lida
     */
    private static Resource[] resources(String folder) throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:" + folder + "/V*__*.sql");
    }

    /**
     * Hash sha256 em hexadecimal minúsculo (o formato do {@code sha256sum}).
     *
     * @param content bytes do arquivo
     * @return o hash
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
