package br.com.kntap.raykookie.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.api.events.OwnerNotice;
import br.com.kntap.raykookie.notifications.application.ports.PushSender;
import br.com.kntap.raykookie.notifications.domain.PushMessage;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O aviso só sai depois do commit; aparelho morto é apagado.
 */
@SpringBootTest(properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
    "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,classpath:db/testdata"})
class NoticeDeliveryIT extends PostgresTestBase {

    /** O que foi mandado. */
    static final List<PushMessage> SENT = new CopyOnWriteArrayList<>();

    /**
     * Envio de teste: grava as mensagens e diz que o token "morto" não existe mais.
     */
    @TestConfiguration
    static class RecordingSender {

        /**
         * O envio de teste.
         *
         * @return o envio
         */
        @Bean
        @Primary
        PushSender recordingSender() {
            return messages -> {
                SENT.addAll(messages);
                return Optional.of(messages.stream()
                        .map(m -> new PushSender.PushResult(m.token(), true, m.token().startsWith("morto"))).toList());
            };
        }
    }

    /** Eventos. */
    @Autowired
    ApplicationEventPublisher events;

    /** Transações. */
    @Autowired
    TransactionTemplate transactions;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /**
     * Dois aparelhos da dona, um vivo e um morto, e nada mandado ainda.
     */
    @BeforeEach
    void devices() {
        SENT.clear();
        jdbc.sql("DELETE FROM device_tokens").update();
        jdbc.sql("INSERT INTO device_tokens (user_id, token, sound) SELECT id, 'vivo-aparelho-0123456789', 'alerta' "
                + "FROM users WHERE username = 'dona'").update();
        jdbc.sql("INSERT INTO device_tokens (user_id, token) SELECT id, 'morto-aparelho-0123456789' "
                + "FROM users WHERE username = 'dona'").update();
    }

    /**
     * Id da dona.
     *
     * @return o id
     */
    private OwnerId owner() {
        return new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
    }

    /**
     * Espera os avisos assíncronos.
     *
     * @param count quantos
     * @throws InterruptedException se interrompido
     */
    private static void await(int count) throws InterruptedException {
        for (int i = 0; i < 50 && SENT.size() < count; i++) {
            Thread.sleep(100);
        }
    }

    /**
     * Commit: sai para todos os aparelhos, cada um com o seu som; o morto é apagado.
     *
     * @throws InterruptedException se interrompido
     */
    @Test
    @DisplayName("commit: aviso sai para cada aparelho e o morto é apagado")
    void deliveredAfterCommit() throws InterruptedException {

        // ── 1. Publica dentro de uma transação que comita.
        OwnerId owner = owner();
        transactions.executeWithoutResult(status -> events.publishEvent(
                new OwnerNotice.NewOrder(owner, 7, 1234, "Joana", Money.ofCents(4550), 3)));

        // ── 2. Dois envios, cada um no canal do seu som.
        await(2);
        assertThat(SENT).extracting(PushMessage::channelId).containsExactlyInAnyOrder("pedido_alerta", "pedido_sino");

        // ── 3. O morto some.
        for (int i = 0; i < 50 && count() > 1; i++) {
            Thread.sleep(100);
        }
        assertThat(count()).isEqualTo(1);
    }

    /**
     * Rollback: nenhum aviso.
     *
     * @throws InterruptedException se interrompido
     */
    @Test
    @DisplayName("rollback: nenhum aviso sai")
    void nothingOnRollback() throws InterruptedException {
        OwnerId owner = owner();
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new OwnerNotice.NewOrder(owner, 8, 1235, "Joana", Money.ofCents(100), 1));
            status.setRollbackOnly();
        });
        Thread.sleep(800);
        assertThat(SENT).isEmpty();
    }

    /**
     * Aparelhos da dona no banco.
     *
     * @return quantos
     */
    private int count() {
        return jdbc.sql("SELECT count(*) FROM device_tokens").query(Integer.class).single();
    }
}
