package br.com.kntap.raykookie.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio parado que o teste avança à mão. Cópias com outro fuso ({@link #withZone(ZoneId)}) dividem o
 * mesmo instante: avançar uma avança todas.
 */
public final class ManualClock extends Clock {

    /** Instante atual, dividido entre as cópias. */
    private final AtomicReference<Instant> now;

    /** Fuso desta cópia. */
    private final ZoneId zone;

    /**
     * Cria o relógio parado num instante, em UTC.
     *
     * @param start instante inicial
     */
    public ManualClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    /**
     * Cria uma cópia que divide o instante.
     *
     * @param now  instante dividido
     * @param zone fuso da cópia
     */
    private ManualClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    /**
     * Avança o relógio.
     *
     * @param amount quanto avançar
     */
    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    /**
     * Põe o relógio num instante.
     *
     * @param instant o instante
     */
    public void set(Instant instant) {
        now.set(instant);
    }

    /**
     * Fuso desta cópia.
     *
     * @return o fuso
     */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /**
     * Cópia em outro fuso, com o mesmo instante.
     *
     * @param newZone o fuso
     * @return a cópia
     */
    @Override
    public Clock withZone(ZoneId newZone) {
        return new ManualClock(now, newZone);
    }

    /**
     * Instante atual.
     *
     * @return o instante
     */
    @Override
    public Instant instant() {
        return now.get();
    }
}
