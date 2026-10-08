package br.com.kntap.raykookie.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Impede o Java de rodar contra um banco de produção num estado que os testes não cobrem: a tabela
 * {@code _migrations} do Node precisa conter exatamente as migrations copiadas para backend-java
 * (as 21 de {@code 001_init.sql} a {@code 021_card_payment.sql}, listadas em {@value #CHECKSUMS}).
 * Se faltar alguma ou houver uma a mais, o servidor não sobe e a mensagem cita os nomes divergentes.
 */
public class ProductionSchemaGuard {

    /** Lista dos originais do Node (formato do {@code sha256sum}: {@code <hash>  <nome>}). */
    static final String CHECKSUMS = "db/migration/production/SHA256SUMS";

    /** Cliente do banco de produção. */
    private final JdbcClient jdbc;

    /** Nomes que a {@code _migrations} precisa ter, em ordem. */
    private final List<String> expected;

    /**
     * Prepara a guarda sobre o banco da aplicação.
     *
     * @param dataSource banco de produção
     */
    public ProductionSchemaGuard(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.expected = expectedMigrations();
    }

    /**
     * Confere a {@code _migrations}; não faz nada se ela estiver exatamente como esperado.
     *
     * @throws IllegalStateException se a tabela não existir ou divergir das migrations conhecidas
     */
    public void verify() {

        // ── 1. Sem a tabela do Node, este não é o banco de produção.
        boolean exists = jdbc.sql("SELECT to_regclass('_migrations') IS NOT NULL").query(Boolean.class).single();
        if (!exists) {
            throw new IllegalStateException("Banco sem a tabela _migrations do backend Node; no perfil prod o "
                    + "servidor só sobe sobre o banco de produção já migrado (001 a 021).");
        }

        // ── 2. Compara o que foi aplicado com o que os testes cobrem.
        List<String> applied = jdbc.sql("SELECT name FROM _migrations ORDER BY name").query(String.class).list();
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(applied);
        List<String> extra = new ArrayList<>(applied);
        extra.removeAll(expected);

        // ── 3. Qualquer divergência impede a subida.
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalStateException("Banco de produção num estado que os testes não cobrem; o servidor "
                    + "não sobe. Faltam em _migrations: " + describe(missing) + ". Sobram em _migrations: "
                    + describe(extra) + ".");
        }
    }

    /**
     * Lê os nomes originais das migrations do Node a partir do {@value #CHECKSUMS} do classpath.
     *
     * @return os nomes, na ordem do arquivo
     */
    static List<String> expectedMigrations() {
        try {
            String sums = new ClassPathResource(CHECKSUMS).getContentAsString(StandardCharsets.UTF_8);
            return sums.lines().filter(line -> !line.isBlank()).map(line -> line.split("\\s+", 2)[1]).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + CHECKSUMS, e);
        }
    }

    /**
     * Lista de nomes para a mensagem de erro.
     *
     * @param names nomes divergentes
     * @return os nomes separados por vírgula, ou "nenhuma"
     */
    private static String describe(List<String> names) {
        if (names.isEmpty()) {
            return "nenhuma";
        }
        return String.join(", ", names);
    }
}
