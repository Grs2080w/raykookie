package br.com.kntap.raykookie.persistence;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Liga o banco: pool Hikari a partir de {@link DatabaseProperties} com os tempos limite de
 * {@code spring.datasource.hikari.*} (G06) e, no perfil {@code prod}, a {@link ProductionSchemaGuard}
 * antes do Flyway migrar.
 *
 * <p>O Flyway em si é o da autoconfiguração do Spring Boot; os dois modos (banco novo e banco de
 * produção com baseline 21) ficam em {@code persistence.yml}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class PersistenceConfig {

    /** Nome do pool nos logs e nas métricas. */
    static final String POOL_NAME = "rayka";

    /**
     * Pool de conexões. Os ajustes de {@code spring.datasource.hikari.*} (tamanho, tempos limite,
     * {@code connection-init-sql}) são aplicados pelo Spring sobre o objeto devolvido.
     *
     * @param database URL e credenciais do banco
     * @return o pool (conecta só no primeiro uso)
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DatabaseProperties database) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName(POOL_NAME);
        pool.setJdbcUrl(database.url());
        pool.setUsername(database.username());
        pool.setPassword(database.password());
        return pool;
    }

    /**
     * Guarda do esquema de produção (só no perfil {@code prod}).
     *
     * @param dataSource pool da aplicação
     * @return a guarda
     */
    @Bean
    @Profile("prod")
    public ProductionSchemaGuard productionSchemaGuard(DataSource dataSource) {
        return new ProductionSchemaGuard(dataSource);
    }

    /**
     * No perfil {@code prod}, confere o estado do banco antes de deixar o Flyway migrar: num banco
     * fora do estado coberto pelos testes, o servidor não sobe.
     *
     * @param guard guarda do esquema de produção
     * @return a estratégia de migração usada pela autoconfiguração do Flyway
     */
    @Bean
    @Profile("prod")
    public FlywayMigrationStrategy guardedMigration(ProductionSchemaGuard guard) {
        return flyway -> {
            guard.verify();
            flyway.migrate();
        };
    }
}
