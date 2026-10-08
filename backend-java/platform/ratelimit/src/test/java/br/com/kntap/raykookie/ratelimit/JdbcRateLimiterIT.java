package br.com.kntap.raykookie.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.testing.ManualClock;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Limites de tentativa com o Postgres real: contagem, janela, várias instâncias e concorrência (G05, G23).
 */
@SpringBootTest
class JdbcRateLimiterIT extends PostgresTestBase {

    /** Limite do login: 8 em 10 minutos. */
    private static final RateLimit LOGIN = RateLimits.OWNER_LOGIN;

    /** Instante inicial dos testes (meio de uma janela de 10 minutos). */
    private static final Instant START = Instant.parse("2026-10-08T12:03:00Z");

    /** O limitador da aplicação. */
    @Autowired
    RateLimiter limiter;

    /** Cliente JDBC da aplicação. */
    @Autowired
    JdbcClient jdbc;

    /** Relógio controlado. */
    @Autowired
    ManualClock clock;

    /**
     * Cada teste começa com a tabela vazia e o relógio no mesmo instante.
     */
    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM rate_limits").update();
        clock.set(START);
    }

    /**
     * Oito tentativas passam; a nona não (o número do Node para login).
     */
    @Test
    @DisplayName("8 tentativas passam e a 9ª é barrada")
    void ninthHitIsBlocked() {

        // ── 1. Oito tentativas do mesmo usuário e IP.
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(limiter.hit(LOGIN, "ana|203.0.113.7"));
        }

        // ── 2. Todas passam; a nona, não; e o peek concorda sem somar.
        assertThat(results).containsOnly(true);
        assertThat(limiter.peek(LOGIN, "ana|203.0.113.7")).isFalse();
        assertThat(limiter.hit(LOGIN, "ana|203.0.113.7")).isFalse();
        assertThat(hits("ana|203.0.113.7")).isEqualTo(9);
    }

    /**
     * Passados 10 minutos, a janela é outra e as tentativas voltam a passar.
     */
    @Test
    @DisplayName("depois da janela, volta a passar")
    void nextWindowAllowsAgain() {

        // ── 1. Esgota o limite.
        for (int i = 0; i < 9; i++) {
            limiter.hit(LOGIN, "ana|203.0.113.7");
        }

        // ── 2. Avança o relógio 10 minutos.
        clock.advance(Duration.ofMinutes(10));

        // ── 3. Volta a passar.
        assertThat(limiter.peek(LOGIN, "ana|203.0.113.7")).isTrue();
        assertThat(limiter.hit(LOGIN, "ana|203.0.113.7")).isTrue();
    }

    /**
     * Assuntos diferentes têm contadores separados; limites diferentes também.
     */
    @Test
    @DisplayName("assuntos e limites diferentes não se misturam")
    void subjectsAreIndependent() {

        // ── 1. Esgota o limite de um assunto.
        for (int i = 0; i < 9; i++) {
            limiter.hit(LOGIN, "ana|203.0.113.7");
        }

        // ── 2. Outro IP, e o mesmo assunto em outro limite, seguem livres.
        assertThat(limiter.hit(LOGIN, "ana|198.51.100.1")).isTrue();
        assertThat(limiter.hit(RateLimits.CUSTOMER_LOGIN, "ana|203.0.113.7")).isTrue();
    }

    /**
     * Duas instâncias do servidor no mesmo banco somam as tentativas (no Node, cada processo contava
     * sozinho).
     */
    @Test
    @DisplayName("duas instâncias somam as tentativas")
    void instancesShareCounters() {

        // ── 1. Duas instâncias independentes sobre o mesmo banco.
        RateLimiter first = new JdbcRateLimiter(jdbc, clock);
        RateLimiter second = new JdbcRateLimiter(jdbc, clock);

        // ── 2. Quatro tentativas em cada.
        for (int i = 0; i < 4; i++) {
            first.hit(LOGIN, "ana|203.0.113.7");
            second.hit(LOGIN, "ana|203.0.113.7");
        }

        // ── 3. A nona é barrada em qualquer uma delas.
        assertThat(first.hit(LOGIN, "ana|203.0.113.7")).isFalse();
        assertThat(hits("ana|203.0.113.7")).isEqualTo(9);
    }

    /**
     * Cem tentativas simultâneas com limite 50: exatamente 50 passam.
     *
     * @throws Exception se uma thread falhar
     */
    @Test
    @DisplayName("100 tentativas simultâneas com limite 50: exatamente 50 passam")
    void concurrentHitsNeverExceedTheLimit() throws Exception {

        // ── 1. Prepara 100 threads que disparam juntas.
        RateLimit fifty = new RateLimit("teste-concorrencia", 50, Duration.ofHours(1));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // ── 2. Cada uma espera o sinal e tenta.
        try (ExecutorService pool = Executors.newFixedThreadPool(100)) {
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (limiter.hit(fifty, "mesmo-ip")) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // ── 3. Nenhuma a mais, nenhuma a menos.
        assertThat(allowed.get()).isEqualTo(50);
    }

    /**
     * Zerar o contador libera o assunto (ex.: login certo).
     */
    @Test
    @DisplayName("reset zera o contador do assunto")
    void resetClearsTheSubject() {

        // ── 1. Esgota o limite.
        for (int i = 0; i < 9; i++) {
            limiter.hit(LOGIN, "ana|203.0.113.7");
        }

        // ── 2. Zera e tenta de novo.
        limiter.reset(LOGIN, "ana|203.0.113.7");

        // ── 3. Livre outra vez.
        assertThat(limiter.peek(LOGIN, "ana|203.0.113.7")).isTrue();
        assertThat(hits("ana|203.0.113.7")).isZero();
    }

    /**
     * A limpeza diária apaga só as janelas antigas.
     */
    @Test
    @DisplayName("purgeOlderThan apaga só as janelas antigas")
    void purgeRemovesOldWindows() {

        // ── 1. Uma janela de dois dias atrás e uma de agora.
        clock.set(START.minus(Duration.ofDays(2)));
        limiter.hit(LOGIN, "velho");
        clock.set(START);
        limiter.hit(LOGIN, "novo");

        // ── 2. Apaga o que é mais velho que a maior janela.
        int purged = limiter.purgeOlderThan(START.minus(RateLimits.LONGEST_WINDOW));

        // ── 3. Só a antiga sumiu.
        assertThat(purged).isEqualTo(1);
        assertThat(hits("velho")).isZero();
        assertThat(hits("novo")).isEqualTo(1);
    }

    /**
     * Limites mal definidos são recusados na criação.
     */
    @Test
    @DisplayName("limite inválido é recusado")
    void invalidLimit() {
        assertThatThrownBy(() -> new RateLimit("a|b", 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit("ok", 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit("ok", 1, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Total de tentativas gravadas para um assunto do limite de login, em todas as janelas.
     *
     * @param subject o assunto
     * @return a soma das tentativas
     */
    private int hits(String subject) {
        return jdbc.sql("SELECT COALESCE(SUM(hits), 0) FROM rate_limits WHERE key = :key")
                .param("key", LOGIN.key(subject))
                .query(Integer.class)
                .single();
    }
}
