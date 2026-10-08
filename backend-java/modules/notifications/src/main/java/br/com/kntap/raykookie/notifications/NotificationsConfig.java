package br.com.kntap.raykookie.notifications;

import br.com.kntap.raykookie.notifications.adapter.out.fcm.FcmPushSender;
import br.com.kntap.raykookie.notifications.adapter.out.fcm.LogFilePushSender;
import br.com.kntap.raykookie.notifications.application.ports.PushSender;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Liga os avisos: arquivo de teste ({@code PUSH_LOG_PATH}), Firebase ({@code FIREBASE_SERVICE_ACCOUNT_PATH})
 * ou desligado, nessa ordem, como no Node. Os avisos saem em segundo plano ({@code @Async}).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class NotificationsConfig {

    /**
     * O envio escolhido pela configuração.
     *
     * @param logPath        {@code rayka.push-log-path}
     * @param serviceAccount {@code rayka.firebase-service-account-path}
     * @param fcmBase        {@code rayka.fcm.base-url}
     * @param clock          relógio
     * @return o envio
     */
    @Bean
    public PushSender pushSender(@Value("${rayka.push-log-path:}") String logPath,
            @Value("${rayka.firebase-service-account-path:}") String serviceAccount,
            @Value("${rayka.fcm.base-url:https://fcm.googleapis.com}") String fcmBase, Clock clock) {
        if (!logPath.isBlank()) {
            return new LogFilePushSender(Path.of(logPath), clock);
        }
        if (!serviceAccount.isBlank()) {
            return new FcmPushSender(Path.of(serviceAccount), fcmBase, clock);
        }
        return messages -> Optional.empty();
    }
}
