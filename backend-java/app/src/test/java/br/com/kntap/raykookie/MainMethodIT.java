package br.com.kntap.raykookie;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationHook;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Prova que o {@code main} de verdade (que o {@code @SpringBootTest} não chama) sobe a aplicação
 * com os argumentos de linha de comando recebidos.
 */
class MainMethodIT {

    /**
     * Roda o {@code main}, captura o contexto criado por ele e o fecha no fim.
     */
    @Test
    @DisplayName("main sobe o contexto Spring com os argumentos recebidos")
    void mainStartsTheApplication() {

        // ── 1. Prepara a captura do contexto que o main vai criar.
        AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
        SpringApplicationHook hook = application -> new SpringApplicationRunListener() {

            /**
             * Guarda o contexto assim que ele termina de subir.
             *
             * @param context   contexto criado pelo main
             * @param timeTaken tempo de subida
             */
            @Override
            public void started(ConfigurableApplicationContext context, Duration timeTaken) {
                started.set(context);
            }
        };

        // ── 2. Roda o main com o perfil de teste e sem servidor web.
        SpringApplication.withHook(hook, () -> RaykookieApplication.main(new String[] {
            "--spring.profiles.active=test", "--spring.main.web-application-type=none"}));

        // ── 3. O contexto subiu com os argumentos recebidos; fecha aqui para não vazar.
        try (ConfigurableApplicationContext context = started.get()) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().getActiveProfiles()).contains("test");
        }
    }
}
