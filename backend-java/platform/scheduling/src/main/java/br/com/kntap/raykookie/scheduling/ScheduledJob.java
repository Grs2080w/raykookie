package br.com.kntap.raykookie.scheduling;

/**
 * Uma tarefa agendada. Cada módulo registra as suas como beans; o {@link JobRunner} decide quando rodar,
 * garante uma instância por vez e registra o resultado em {@code job_runs}.
 *
 * <p>A tarefa roda fora de transação: cada passo dela abre a sua (uma falha no meio não desfaz no banco o
 * que já foi feito fora dele, como uma devolução já pedida ao Mercado Pago).
 */
public interface ScheduledJob {

    /**
     * Nome único da tarefa (ex.: {@code refunds.retry-due}); é a chave em {@code job_runs} e na trava.
     *
     * @return o nome
     */
    String name();

    /**
     * Com que frequência a tarefa roda.
     *
     * @return a frequência
     */
    JobFrequency frequency();

    /**
     * Diz se a tarefa roda mesmo com as tarefas desligadas ({@code JOBS_ENABLED=false}, enquanto o Node usa o mesmo
     * banco): só as que mexem em dados que o Node não conhece (como {@code payment_attempts}) e por isso não rodam em
     * dobro.
     *
     * @return verdadeiro se roda sempre
     */
    default boolean runsAlongsideNode() {
        return false;
    }

    /**
     * Faz o trabalho. Lançar uma exceção registra a falha e dispara um alerta; as outras tarefas seguem.
     */
    void run();
}
