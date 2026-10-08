package br.com.kntap.raykookie.scheduling;

/**
 * O que aconteceu com uma tarefa num ciclo do {@link JobRunner}.
 */
public enum JobOutcome {

    /** Rodou e deu certo. */
    RAN,

    /** Rodou e falhou (registrado em {@code job_runs} e alertado). */
    FAILED,

    /** Ainda não estava na hora. */
    NOT_DUE,

    /** Outra instância está rodando a mesma tarefa agora. */
    LOCKED_ELSEWHERE
}
