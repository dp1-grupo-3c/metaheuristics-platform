package org.kindbox.core.modelo;

/**
 * Turnos de 8 horas del enunciado, con cambios a las 07:00, 15:00 y 23:00.
 * El turno {@link #NOCHE} cruza la medianoche.
 */
public enum Turno {

    /** 07:00 a 15:00. */
    MANANA(420, 900),
    /** 15:00 a 23:00. Es el turno de reincorporacion de las averias de tipo 3. */
    TARDE(900, 1380),
    /** 23:00 a 07:00 del dia siguiente. */
    NOCHE(1380, 1860);

    /** Duracion de un turno en minutos. */
    public static final int DURACION_MIN = 480;
    /** Minutos del dia en los que ocurre un cambio de turno. */
    public static final int[] CAMBIOS_DE_TURNO = {420, 900, 1380};

    private final int inicioMinutoDia;
    private final int finMinutoDia;

    Turno(int inicioMinutoDia, int finMinutoDia) {
        this.inicioMinutoDia = inicioMinutoDia;
        this.finMinutoDia = finMinutoDia;
    }

    /** Minuto del dia en que arranca el turno (puede ser >= 1440 al cruzar medianoche el fin). */
    public int inicioMinutoDia() {
        return inicioMinutoDia;
    }

    /** Minuto en que cierra el turno, medido desde las 00:00 del dia en que arranco. */
    public int finMinutoDia() {
        return finMinutoDia;
    }

    /** Turno al que pertenece un instante absoluto medido en minutos desde el inicio del escenario. */
    public static Turno enMinuto(long minutoAbsoluto) {
        int minutoDia = (int) Math.floorMod(minutoAbsoluto, 1440L);
        if (minutoDia < 420) {
            return NOCHE;
        }
        if (minutoDia < 900) {
            return MANANA;
        }
        if (minutoDia < 1380) {
            return TARDE;
        }
        return NOCHE;
    }

    /**
     * Instante absoluto en que arranco el turno que contiene al minuto dado.
     * Para el turno noche entre las 00:00 y las 07:00 el arranque es el dia anterior.
     */
    public static long inicioDelTurno(long minutoAbsoluto) {
        long dia = Math.floorDiv(minutoAbsoluto, 1440L);
        int minutoDia = (int) Math.floorMod(minutoAbsoluto, 1440L);
        if (minutoDia < 420) {
            return (dia - 1) * 1440L + 1380L;
        }
        if (minutoDia < 900) {
            return dia * 1440L + 420L;
        }
        if (minutoDia < 1380) {
            return dia * 1440L + 900L;
        }
        return dia * 1440L + 1380L;
    }

    /** Instante absoluto en que cierra el turno que contiene al minuto dado. */
    public static long finDelTurno(long minutoAbsoluto) {
        return inicioDelTurno(minutoAbsoluto) + DURACION_MIN;
    }

    /** Instante absoluto en que cierra el turno siguiente al que contiene al minuto dado. */
    public static long finDelTurnoSiguiente(long minutoAbsoluto) {
        return finDelTurno(minutoAbsoluto) + DURACION_MIN;
    }

    /** Primer instante absoluto {@code >= desde} en que arranca el turno de tarde (15:00). */
    public static long proximoInicioDeTarde(long desde) {
        long dia = Math.floorDiv(desde, 1440L);
        long candidato = dia * 1440L + TARDE.inicioMinutoDia;
        return candidato >= desde ? candidato : candidato + 1440L;
    }
}
