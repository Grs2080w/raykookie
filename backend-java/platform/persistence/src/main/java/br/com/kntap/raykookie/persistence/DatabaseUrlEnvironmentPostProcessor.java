package br.com.kntap.raykookie.persistence;

import br.com.kntap.raykookie.persistence.DatabaseUrlParser.Coordinates;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Se houver {@code DATABASE_URL} (o formato do Node) e nenhuma {@code rayka.db.url}, preenche
 * {@code rayka.db.url}, {@code rayka.db.username} e {@code rayka.db.password} a partir dela.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Nome da fonte de propriedades criada. */
    static final String SOURCE_NAME = "raykaDatabaseUrl";

    /**
     * Converte a {@code DATABASE_URL}, quando for o caso.
     *
     * @param environment ambiente do Spring
     * @param application a aplicação
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {

        // ── 1. Só age com DATABASE_URL presente e sem uma URL JDBC explícita.
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank() || environment.containsProperty("rayka.db.url")) {
            return;
        }

        // ── 2. Converte e publica com prioridade baixa (qualquer rayka.db.* explícito vence).
        Coordinates coordinates = DatabaseUrlParser.parse(databaseUrl);
        Map<String, Object> properties = new HashMap<>();
        properties.put("rayka.db.url", coordinates.jdbcUrl());
        if (coordinates.username() != null) {
            properties.put("rayka.db.username", coordinates.username());
        }
        if (coordinates.password() != null) {
            properties.put("rayka.db.password", coordinates.password());
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, properties));
    }
}
