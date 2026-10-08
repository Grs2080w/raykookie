package br.com.kntap.raykookie.health.adapter.in.web;

import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.web.ErrorBody;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saúde do servidor e o fim do {@code /api/jobs/tick} (Tarefa 6.4, G13).
 */
@RestController
public class HealthController {

    /** Tempo máximo do {@code SELECT 1}: banco travado responde 503 rápido, não trava o deploy (T09). */
    static final Duration DATABASE_TIMEOUT = Duration.ofSeconds(2);

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o controller.
     *
     * @param jdbc cliente JDBC sobre o pool da aplicação
     */
    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 200 {@code {"ok":true}} com o banco respondendo em até 2 s; senão 503 {@code {"ok":false}}, como o Node.
     *
     * @return a resposta de saúde
     */
    @Access(AccessLevel.PUBLIC)
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        CompletableFuture<Integer> ping = CompletableFuture.supplyAsync(
                () -> jdbc.sql("SELECT 1").query(Integer.class).single(), Thread::startVirtualThread);
        try {
            ping.get(DATABASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (ExecutionException | TimeoutException databaseDown) {
            ping.cancel(true);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("ok", false));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("ok", false));
        }
    }

    /**
     * O antigo gatilho externo das tarefas: as tarefas agora rodam dentro do servidor ({@code JobTicker}).
     * Responde 410 para nenhum agendador antigo achar que ainda funciona.
     *
     * @return 410 com a explicação
     */
    @Access(AccessLevel.PUBLIC)
    @PostMapping("/api/jobs/tick")
    public ResponseEntity<ErrorBody> jobsTick() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorBody("Tarefas agendadas rodam dentro do servidor."));
    }
}
