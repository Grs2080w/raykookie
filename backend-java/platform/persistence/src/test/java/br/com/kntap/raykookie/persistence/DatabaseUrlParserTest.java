package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.persistence.DatabaseUrlParser.Coordinates;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * O {@code DATABASE_URL} do Node vira as coordenadas JDBC do Java.
 */
class DatabaseUrlParserTest {

    /**
     * O exemplo do plano.
     */
    @Test
    @DisplayName("postgres://u:p@h:5432/db vira jdbc:postgresql://h:5432/db com u e p")
    void planExample() {
        Coordinates db = DatabaseUrlParser.parse("postgres://u:p@h:5432/db");
        assertThat(db).isEqualTo(new Coordinates("jdbc:postgresql://h:5432/db", "u", "p"));
    }

    /**
     * Senha com caracteres codificados, porta padrão e opções mantidas.
     */
    @Test
    @DisplayName("senha codificada, porta padrão e opções da URL")
    void encodedPasswordDefaultPortAndOptions() {
        Coordinates db = DatabaseUrlParser.parse(" postgresql://rayka:s%40nh%2Fa+1@db.local/rayka_estoque?sslmode=require ");
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:postgresql://db.local:5432/rayka_estoque?sslmode=require");
        assertThat(db.username()).isEqualTo("rayka");
        assertThat(db.password()).isEqualTo("s@nh/a+1");
    }

    /**
     * Sem usuário na URL, ou só com usuário.
     */
    @Test
    @DisplayName("URL sem senha ou sem usuário")
    void withoutCredentials() {
        assertThat(DatabaseUrlParser.parse("postgres://h/db")).isEqualTo(new Coordinates("jdbc:postgresql://h:5432/db", null, null));
        assertThat(DatabaseUrlParser.parse("postgres://u@h/db").username()).isEqualTo("u");
        assertThat(DatabaseUrlParser.parse("postgres://u@h/db").password()).isNull();
    }

    /**
     * URL inválida impede a subida, sem mostrar a senha.
     *
     * @param url URL inválida
     */
    @ParameterizedTest
    @ValueSource(strings = {"mysql://u:segredo@h/db", "postgres://u:segredo@h", "postgres://u:segredo@h/", "nada aqui"})
    @DisplayName("URL que não é de Postgres ou sem banco: erro sem a senha")
    void invalid(String url) {
        assertThatThrownBy(() -> DatabaseUrlParser.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DATABASE_URL")
                .hasMessageNotContaining("segredo");
    }

    /**
     * O pós-processador preenche rayka.db.* só quando não há URL JDBC explícita.
     */
    @Test
    @DisplayName("DATABASE_URL preenche rayka.db.*; rayka.db.url explícita vence")
    void environmentPostProcessor() {

        // ── 1. Só DATABASE_URL: vira rayka.db.*.
        StandardEnvironment onlyNode = environment(Map.of("DATABASE_URL", "postgres://u:p@h:5432/db"));
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(onlyNode, null);

        // ── 2. Com rayka.db.url explícita: nada muda.
        StandardEnvironment explicit = environment(Map.of("DATABASE_URL", "postgres://u:p@h:5432/db",
                "rayka.db.url", "jdbc:postgresql://outro:1/x"));
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(explicit, null);

        // ── 3. Sem DATABASE_URL: nada muda.
        StandardEnvironment none = environment(Map.of());
        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(none, null);

        // ── 4. Confere os três casos.
        assertThat(onlyNode.getProperty("rayka.db.url")).isEqualTo("jdbc:postgresql://h:5432/db");
        assertThat(onlyNode.getProperty("rayka.db.username")).isEqualTo("u");
        assertThat(onlyNode.getProperty("rayka.db.password")).isEqualTo("p");
        assertThat(explicit.getProperty("rayka.db.url")).isEqualTo("jdbc:postgresql://outro:1/x");
        assertThat(explicit.getProperty("rayka.db.username")).isNull();
        assertThat(none.containsProperty("rayka.db.url")).isFalse();
    }

    /**
     * Ambiente só com as propriedades dadas (sem as variáveis da máquina que roda o teste).
     *
     * @param properties propriedades
     * @return o ambiente
     */
    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("teste", properties));
        return environment;
    }
}
