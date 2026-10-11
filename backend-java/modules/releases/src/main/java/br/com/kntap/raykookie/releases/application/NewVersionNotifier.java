package br.com.kntap.raykookie.releases.application;

import br.com.kntap.raykookie.kernel.events.AppReleased;
import br.com.kntap.raykookie.releases.application.ports.ReleaseNotices;
import br.com.kntap.raykookie.scheduling.JobFrequency;
import br.com.kntap.raykookie.scheduling.ScheduledJob;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * De 5 em 5 minutos, confere o manifesto de downloads: build novo de um app (o CD acabou de publicar) avisa todos os
 * aparelhos daquele app pelo push, uma vez só. Na primeira vez, só registra o build atual.
 */
@Component
public class NewVersionNotifier implements ScheduledJob {

    /** Versões publicadas. */
    private final Releases releases;

    /** Último build avisado. */
    private final ReleaseNotices notices;

    /** Eventos (o módulo de avisos entrega). */
    private final ApplicationEventPublisher events;

    /**
     * Cria a tarefa.
     *
     * @param releases versões publicadas
     * @param notices  último build avisado
     * @param events   eventos
     */
    public NewVersionNotifier(Releases releases, ReleaseNotices notices, ApplicationEventPublisher events) {
        this.releases = releases;
        this.notices = notices;
        this.events = events;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "releases.notify-new-version";
    }

    /** {@inheritDoc} */
    @Override
    public JobFrequency frequency() {
        return JobFrequency.EVERY_5_MIN;
    }

    /** {@inheritDoc} */
    @Override
    public void run() {
        releases.manifest().forEach(this::check);
    }

    /**
     * Um app: build maior que o último avisado vira aviso; o primeiro só é registrado.
     *
     * @param app     {@code client} ou {@code server}
     * @param release a versão publicada (campos do manifesto)
     */
    private void check(String app, Map<String, Object> release) {

        // ── 1. Build publicado (manifesto sem build: nada a fazer).
        if (!(release.get("build") instanceof Number number)) {
            return;
        }
        int build = number.intValue();

        // ── 2. Primeira vez registra; build maior avisa e registra.
        Optional<Integer> last = notices.lastBuild(app);
        if (last.isPresent() && build <= last.get()) {
            return;
        }
        if (last.isPresent()) {
            events.publishEvent(new AppReleased(app, String.valueOf(release.get("version")), build));
        }
        notices.save(app, build);
    }
}
