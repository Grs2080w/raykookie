package br.com.kntap.raykookie.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Prova que a limpeza de saída apaga os containers de uma sessão e o Ryuk dela, e só os dela.
 */
class ContainerCleanupIT extends PostgresTestBase {

    /**
     * Uma sessão falsa com um container rotulado e um "Ryuk" pelo nome: os dois somem, o Postgres desta
     * JVM (outra sessão) fica.
     */
    @Test
    @DisplayName("apaga os containers e o Ryuk da sessão, sem tocar nas outras")
    void removesOnlyTheSession() {

        // ── 1. Dois containers parados da sessão falsa, criados com a imagem que já está no host.
        DockerClient docker = DockerClientFactory.instance().client();
        String session = "cleanup-it-" + UUID.randomUUID();
        String labeled = docker.createContainerCmd(IMAGE).withLabels(Map.of(ContainerCleanup.SESSION_LABEL, session))
                .exec().getId();
        String ryuk = docker.createContainerCmd(IMAGE).withName(ContainerCleanup.RYUK_PREFIX + session)
                .exec().getId();

        // ── 2. Limpa a sessão falsa.
        ContainerCleanup.removeSession(session);

        // ── 3. Os dois sumiram; o Postgres compartilhado continua de pé.
        List<String> remaining = docker.listContainersCmd().withShowAll(true)
                .withIdFilter(List.of(labeled, ryuk)).exec().stream().map(c -> c.getId()).toList();
        assertThat(remaining).isEmpty();
        assertThat(postgres().isRunning()).isTrue();
    }
}
