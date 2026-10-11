package br.com.kntap.raykookie.releases;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.events.AppReleased;
import br.com.kntap.raykookie.releases.application.NewVersionNotifier;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Aviso de versão nova: o primeiro build só é registrado; um build maior avisa uma vez.
 */
@RecordApplicationEvents
@SpringBootTest(properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
    "rayka.download-secret=segredo-de-download-com-mais-de-32-caracteres",
    "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
            + "classpath:db/testdata"})
class NewVersionNotifierIT extends PostgresTestBase {

    /** Pasta dos downloads. */
    private static Path dir;

    /** A tarefa. */
    @Autowired
    NewVersionNotifier notifier;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Eventos publicados. */
    @Autowired
    ApplicationEvents events;

    /**
     * Pasta temporária dos downloads.
     *
     * @param registry propriedades
     * @throws IOException se não der para criar
     */
    @DynamicPropertySource
    static void downloads(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("notifier-it");
        registry.add("rayka.downloads-dir", dir::toString);
    }

    /**
     * Publica um build do app do cliente.
     *
     * @param build build
     * @throws IOException se não der para gravar
     */
    private static void publish(int build) throws IOException {
        String file = "raykookie-1.0.0+" + build + ".apk";
        Files.writeString(dir.resolve(file), "apk");
        Files.writeString(dir.resolve("manifest.json"), "{\"client\":{\"app\":\"client\",\"version\":\"1.0.0\","
                + "\"build\":" + build + ",\"file\":\"" + file + "\",\"sha256\":\"x\"}}");
    }

    /**
     * Primeiro build, repetição e build novo.
     *
     * @throws IOException se não der para gravar
     */
    @Test
    @DisplayName("primeiro build só registra; o mesmo não avisa de novo; build maior avisa uma vez")
    void notifiesOncePerNewBuild() throws IOException {

        // ── 1. Primeira vez: registra o build atual, sem aviso.
        jdbc.sql("DELETE FROM release_notices").update();
        publish(3);
        notifier.run();
        notifier.run();
        assertThat(events.stream(AppReleased.class)).isEmpty();
        assertThat(jdbc.sql("SELECT build FROM release_notices WHERE app = 'client'").query(Integer.class).single())
                .isEqualTo(3);

        // ── 2. Build novo: um aviso, e o registro anda.
        publish(5);
        notifier.run();
        notifier.run();
        assertThat(events.stream(AppReleased.class)).containsExactly(new AppReleased("client", "1.0.0", 5));
        assertThat(jdbc.sql("SELECT build FROM release_notices WHERE app = 'client'").query(Integer.class).single())
                .isEqualTo(5);
        assertThat(notifier.name()).isEqualTo("releases.notify-new-version");
    }
}
