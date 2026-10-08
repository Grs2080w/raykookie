package br.com.kntap.raykookie.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/jobs/tick} (Tarefa 6.4).
 */
class JobsTickContractIT extends ContractTestBase {

    /**
     * No Node, sem o segredo: 401 (só vale para o Node; no Java a rota mudou de propósito).
     */
    @Test
    @DisplayName("Node: sem x-jobs-secret, 401")
    void nodeRequiresSecret() {
        Assumptions.assumeFalse(target.isJava(), "comportamento do Node, trocado no Java (G13)");
        assertError(api().post("/api/jobs/tick"), 401, "Não autorizado");
    }

    /**
     * No Java as tarefas rodam dentro do servidor; o gatilho externo responde 410.
     */
    @Test
    @ChangedFromNode("G13")
    @DisplayName("Java: 410, as tarefas rodam dentro do servidor")
    void javaSaysGone() {
        assertError(api().post("/api/jobs/tick"), 410, "Tarefas agendadas rodam dentro do servidor.");
    }
}
