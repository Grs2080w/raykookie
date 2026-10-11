package br.com.kntap.raykookie.testing;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testcontainers.DockerClientFactory;

/**
 * Rede de segurança que apaga os containers desta JVM quando ela termina, sem depender só do Ryuk.
 *
 * <p>O Ryuk apaga os containers da sessão quando fica 10 s sem nenhum cliente conectado. Qualquer
 * conexão TCP na porta dele conta como cliente, até uma que não manda nada; um monitor de portas no
 * host (o PortPulse, por exemplo, abre e fecha uma conexão a cada 2 s em toda porta aberta) zera esse
 * prazo para sempre, e o Ryuk e o Postgres ficam de pé até a máquina desligar. Por isso, além do Ryuk:
 *
 * <ul>
 *   <li>um shutdown hook apaga os containers da sessão (e o próprio Ryuk) quando a JVM sai normalmente,
 *       recebe SIGINT/SIGTERM ou é encerrada pelo surefire/failsafe ({@code System.exit});</li>
 *   <li>um vigia ({@code sh} numa sessão própria, via {@code setsid}) espera a JVM morrer e faz o mesmo
 *       com o {@code docker} da linha de comando. Ele cobre o SIGKILL, que não roda shutdown hooks, e
 *       não recebe o Ctrl+C nem o kill do grupo de processos do Maven. Sem {@code setsid} ou
 *       {@code docker} no PATH, o vigia não sobe e ficam o hook e o Ryuk.</li>
 * </ul>
 */
final class ContainerCleanup {

    /** Rótulo que o Testcontainers põe em todo container da sessão (menos no Ryuk). */
    static final String SESSION_LABEL = "org.testcontainers.sessionId";

    /** Prefixo do nome do container do Ryuk; o resto é o id da sessão. */
    static final String RYUK_PREFIX = "testcontainers-ryuk-";

    /**
     * Script do vigia: espera o processo {@code $1} morrer e apaga, com os volumes, os containers da
     * sessão {@code $2} e o Ryuk dela.
     */
    private static final String WATCHDOG_SCRIPT = """
            while kill -0 "$1" 2>/dev/null; do sleep 2; done
            ids=$(docker ps -aq --filter "label=%s=$2"; docker ps -aq --filter "name=%s$2")
            [ -n "$ids" ] && docker rm -fv $ids >/dev/null 2>&1
            exit 0
            """.formatted(SESSION_LABEL, RYUK_PREFIX);

    /** Se a limpeza já foi armada nesta JVM. */
    private static boolean armed;

    /** Só métodos estáticos. */
    private ContainerCleanup() {
    }

    /**
     * Arma o shutdown hook e o vigia da sessão atual do Testcontainers. Chamadas repetidas não fazem nada.
     */
    static synchronized void arm() {

        // ── 1. Uma vez por JVM.
        if (armed) {
            return;
        }
        armed = true;

        // ── 2. Hook para as saídas em que a JVM ainda roda código; vigia para o SIGKILL.
        String session = DockerClientFactory.SESSION_ID;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> removeSession(session), "container-cleanup"));
        startWatchdog(ProcessHandle.current().pid(), session);
    }

    /**
     * Apaga, com os volumes, os containers da sessão e depois o Ryuk dela. Erros são ignorados: a JVM está
     * saindo e o vigia tenta de novo.
     *
     * @param session id da sessão do Testcontainers
     */
    static void removeSession(String session) {

        // ── 1. Containers da sessão primeiro, Ryuk por último.
        try {
            DockerClient docker = DockerClientFactory.instance().client();
            List<Container> leftovers = new ArrayList<>(docker.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(SESSION_LABEL, session)).exec());
            leftovers.addAll(docker.listContainersCmd().withShowAll(true)
                    .withNameFilter(List.of(RYUK_PREFIX + session)).exec());

            // ── 2. Força a remoção de cada um, levando os volumes anônimos (o do Postgres, por exemplo).
            for (Container container : leftovers) {
                removeQuietly(docker, container.getId());
            }
        } catch (RuntimeException e) {
            // O Docker pode já não responder; o vigia tenta de novo.
        }
    }

    /**
     * Apaga um container e os volumes anônimos dele, ignorando "já removido" e afins.
     *
     * @param docker cliente do Docker
     * @param id id do container
     */
    private static void removeQuietly(DockerClient docker, String id) {
        try {
            docker.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException e) {
            // Já removido, ou o Ryuk (auto-remove) está saindo sozinho.
        }
    }

    /**
     * Sobe o vigia numa sessão própria, com a entrada e as saídas ligadas a /dev/null (para não prender
     * o pipe do surefire nem morrer de SIGPIPE).
     *
     * @param pid processo desta JVM
     * @param session id da sessão do Testcontainers
     */
    private static void startWatchdog(long pid, String session) {

        // ── 1. Sem as ferramentas, fica só o hook e o Ryuk.
        if (!onPath("setsid") || !onPath("docker") || !onPath("sh")) {
            return;
        }

        // ── 2. Processo desligado desta JVM e do grupo do Maven.
        ProcessBuilder watchdog = new ProcessBuilder("setsid", "sh", "-c", WATCHDOG_SCRIPT, "container-cleanup",
                Long.toString(pid), session)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            watchdog.start();
        } catch (IOException e) {
            // Sem vigia; o hook e o Ryuk continuam valendo.
        }
    }

    /**
     * Diz se um executável está em algum diretório do PATH.
     *
     * @param command nome do executável
     * @return {@code true} se existe e pode ser executado
     */
    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }
}
