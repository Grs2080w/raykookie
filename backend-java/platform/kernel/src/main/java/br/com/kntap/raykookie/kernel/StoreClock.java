package br.com.kntap.raykookie.kernel;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Relógio da loja: o dia e o mês valem no fuso {@code America/Sao_Paulo}, não no do servidor.
 * Construído sobre um {@link Clock} injetável para que os testes controlem o tempo.
 */
public final class StoreClock {

    /** Fuso da loja. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** Relógio de base. */
    private final Clock clock;

    /**
     * Cria o relógio da loja sobre um relógio de base.
     *
     * @param clock relógio de base (qualquer fuso; o instante é o que importa)
     */
    public StoreClock(Clock clock) {
        this.clock = clock.withZone(ZONE);
    }

    /**
     * Relógio da loja ligado ao relógio real do sistema.
     *
     * @return relógio da loja em produção
     */
    public static StoreClock system() {
        return new StoreClock(Clock.systemUTC());
    }

    /**
     * Data de hoje no fuso da loja.
     *
     * @return dia atual
     */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Chave do mês corrente: o dia 1 do mês, no fuso da loja.
     *
     * @return primeiro dia do mês atual
     */
    public LocalDate monthKey() {
        return today().withDayOfMonth(1);
    }
}
