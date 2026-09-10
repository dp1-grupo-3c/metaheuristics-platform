package org.kindbox.core.modelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turnos de ocho horas del apartado 4 del contexto de dominio, con cambios a las 07:00,
 * 15:00 y 23:00.
 *
 * <p>Se comprueban los bordes exactos 06:59, 07:00, 14:59, 15:00, 22:59, 23:00 y 00:00,
 * que es donde un error de un minuto cambia el turno y con el la restriccion dura 3 del
 * apartado 2.6 del ISA, el cierre mas alla del cual ninguna ruta puede extenderse. El turno
 * de noche cruza la medianoche, de modo que las horas anteriores a las 07:00 pertenecen al
 * turno que arranco el dia anterior.</p>
 */
class TurnoTest {

    /** Minuto absoluto de una hora del dia indicado, con el dia 1 como primero. */
    private static long minuto(int dia, int hora, int minuto) {
        return (dia - 1) * 1440L + hora * 60L + minuto;
    }

    @Test
    @DisplayName("06:59 pertenece al turno de noche que arranco el dia anterior")
    void antesDeLasSiete() {
        long instante = minuto(2, 6, 59);
        assertEquals(Turno.NOCHE, Turno.enMinuto(instante));
        assertEquals(minuto(1, 23, 0), Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 7, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(2, 15, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(2, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("07:00 en punto arranca el turno de manana")
    void lasSieteEnPunto() {
        long instante = minuto(2, 7, 0);
        assertEquals(Turno.MANANA, Turno.enMinuto(instante));
        assertEquals(instante, Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 15, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(2, 23, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(2, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("14:59 sigue en el turno de manana")
    void antesDeLasTres() {
        long instante = minuto(2, 14, 59);
        assertEquals(Turno.MANANA, Turno.enMinuto(instante));
        assertEquals(minuto(2, 7, 0), Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 15, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(2, 23, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(2, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("15:00 en punto arranca el turno de tarde")
    void lasTresEnPunto() {
        long instante = minuto(2, 15, 0);
        assertEquals(Turno.TARDE, Turno.enMinuto(instante));
        assertEquals(instante, Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 23, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(3, 7, 0), Turno.finDelTurnoSiguiente(instante));
        // El proximo inicio de tarde es el mismo instante, porque la busqueda no es estricta.
        assertEquals(instante, Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("22:59 sigue en el turno de tarde y ya espera al dia siguiente para reingresar")
    void antesDeLasOnce() {
        long instante = minuto(2, 22, 59);
        assertEquals(Turno.TARDE, Turno.enMinuto(instante));
        assertEquals(minuto(2, 15, 0), Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 23, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(3, 7, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(3, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("23:00 en punto arranca el turno de noche")
    void lasOnceEnPunto() {
        long instante = minuto(2, 23, 0);
        assertEquals(Turno.NOCHE, Turno.enMinuto(instante));
        assertEquals(instante, Turno.inicioDelTurno(instante));
        assertEquals(minuto(3, 7, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(3, 15, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(3, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("La medianoche pertenece al turno nocturno que arranco la vispera")
    void laMedianoche() {
        long instante = minuto(2, 0, 0);
        assertEquals(Turno.NOCHE, Turno.enMinuto(instante));
        assertEquals(minuto(1, 23, 0), Turno.inicioDelTurno(instante));
        assertEquals(minuto(2, 7, 0), Turno.finDelTurno(instante));
        assertEquals(minuto(2, 15, 0), Turno.finDelTurnoSiguiente(instante));
        assertEquals(minuto(2, 15, 0), Turno.proximoInicioDeTarde(instante));
    }

    @Test
    @DisplayName("El minuto cero del escenario cae en el turno nocturno que arranco el dia cero")
    void elMinutoCero() {
        assertEquals(Turno.NOCHE, Turno.enMinuto(0));
        assertEquals(-60L, Turno.inicioDelTurno(0));
        assertEquals(minuto(1, 7, 0), Turno.finDelTurno(0));
        assertEquals(minuto(1, 15, 0), Turno.finDelTurnoSiguiente(0));
        assertEquals(minuto(1, 15, 0), Turno.proximoInicioDeTarde(0));
    }

    @Test
    @DisplayName("Todo instante de un dia completo cae en un turno de ocho horas coherente")
    void invariantesDelDia() {
        for (long m = -1440; m < 3 * 1440; m++) {
            long inicio = Turno.inicioDelTurno(m);
            long fin = Turno.finDelTurno(m);
            assertEquals(Turno.DURACION_MIN, fin - inicio, "el turno del minuto " + m + " no dura ocho horas");
            assertTrue(inicio <= m && m < fin, "el minuto " + m + " no cae en su propio turno");
            assertEquals(inicio, Turno.inicioDelTurno(inicio),
                    "el arranque del turno del minuto " + m + " no es idempotente");
            assertEquals(fin, Turno.finDelTurno(fin - 1), "el cierre del turno del minuto " + m + " no cuadra");
            assertEquals(Turno.enMinuto(m), Turno.enMinuto(inicio),
                    "el minuto " + m + " y el arranque de su turno deben dar el mismo turno");
            int minutoDelDia = (int) Math.floorMod(inicio, 1440L);
            assertTrue(minutoDelDia == 420 || minutoDelDia == 900 || minutoDelDia == 1380,
                    "el turno del minuto " + m + " arranca a una hora que no es un cambio de turno");
        }
    }

    @Test
    @DisplayName("Los cambios de turno declarados son las 07:00, las 15:00 y las 23:00")
    void cambiosDeTurnoDeclarados() {
        assertEquals(3, Turno.CAMBIOS_DE_TURNO.length);
        assertEquals(7 * 60, Turno.CAMBIOS_DE_TURNO[0]);
        assertEquals(15 * 60, Turno.CAMBIOS_DE_TURNO[1]);
        assertEquals(23 * 60, Turno.CAMBIOS_DE_TURNO[2]);
        assertEquals(7 * 60, Turno.MANANA.inicioMinutoDia());
        assertEquals(15 * 60, Turno.TARDE.inicioMinutoDia());
        assertEquals(23 * 60, Turno.NOCHE.inicioMinutoDia());
    }
}
