package br.com.kntap.raykookie.persistence;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Roda algo depois do commit da transação atual (chamadas ao Mercado Pago nunca seguram trava de linha); sem
 * transação, roda na hora.
 */
public final class AfterCommit {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private AfterCommit() {
    }

    /**
     * Agenda a tarefa.
     *
     * @param task tarefa
     */
    public static void run(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** Roda a tarefa agendada, já com a transação confirmada. */
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
