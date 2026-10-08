package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Prova que a configuração do banco exige a URL e liga o pool Hikari com o que foi informado em
 * {@code rayka.db.*} e {@code spring.datasource.hikari.*}.
 */
class DatabasePropertiesTest {

    /** Contexto só com a configuração de persistência (o pool não conecta antes do primeiro uso). */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfig.class);

    /**
     * Sem {@code rayka.db.url}, a aplicação não sobe e diz o que falta.
     */
    @Test
    @DisplayName("sem rayka.db.url a aplicação não sobe")
    void urlIsRequired() {
        runner.withPropertyValues("rayka.db.url=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("rayka.db.url"));
    }

    /**
     * URL, usuário, senha e os ajustes do Hikari chegam ao pool.
     */
    @Test
    @DisplayName("o pool usa a URL, as credenciais e os tempos limite configurados")
    void wiresHikariFromProperties() {
        runner.withPropertyValues("rayka.db.url=jdbc:postgresql://127.0.0.1:1/x", "rayka.db.username=u",
                        "rayka.db.password=p", "spring.datasource.hikari.connection-timeout=3000")
                .run(context -> {
                    HikariDataSource pool = context.getBean(HikariDataSource.class);
                    assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:1/x");
                    assertThat(pool.getUsername()).isEqualTo("u");
                    assertThat(pool.getPassword()).isEqualTo("p");
                    assertThat(pool.getConnectionTimeout()).isEqualTo(3000);
                    assertThat(pool.getPoolName()).isEqualTo("rayka");
                });
    }
}
