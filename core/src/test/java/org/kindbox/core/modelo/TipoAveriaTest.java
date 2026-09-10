package org.kindbox.core.modelo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reglas de reincorporacion de las averias, respuesta 3 del cuestionario y apartado 7 del
 * contexto de dominio.
 *
 * <p>Los valores esperados estan calculados a mano a partir de la tabla del apartado 7 y de
 * los cambios de turno de las 07:00, 15:00 y 23:00. El reloj de referencia arranca a las
 * 00:00 del primer dia, de modo que el minuto {@code d*1440 + h*60 + m} es la hora
 * {@code h:m} del dia {@code d+1}. Se incluyen a proposito los casos que cruzan la
 * medianoche, que es donde el calculo del turno deja de ser una simple division.</p>
 */
class TipoAveriaTest {

    /** Minuto absoluto de una hora concreta del dia indicado, con el dia 1 como primero. */
    private static long minuto(int dia, int hora, int minuto) {
        return (dia - 1) * 1440L + hora * 60L + minuto;
    }

    @Nested
    @DisplayName("Tipo 1, menor: dos horas de indisponibilidad en el mismo lugar")
    class Tipo1 {

        @Test
        @DisplayName("Se reincorpora dos horas despues, sin salir del lugar")
        void dosHorasDespues() {
            assertEquals(minuto(1, 12, 0), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 10, 0)));
            assertEquals(minuto(1, 9, 45), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 7, 45)));
        }

        @Test
        @DisplayName("Cruza la medianoche sin caso especial")
        void cruzaLaMedianoche() {
            // 23:30 del dia 1 mas dos horas es la 01:30 del dia 2.
            assertEquals(minuto(2, 1, 30), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 23, 30)));
            // 23:00 en punto, justo en el cambio de turno.
            assertEquals(minuto(2, 1, 0), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 23, 0)));
        }

        @Test
        @DisplayName("No se traslada al almacen central y permanece dos horas en el lugar")
        void noSeTraslada() {
            assertFalse(TipoAveria.TIPO_1.trasladaAlmacenCentral());
            assertEquals(120, TipoAveria.TIPO_1.minutosEnElLugar());
        }
    }

    @Nested
    @DisplayName("Tipo 2, intermedia: hasta el final del turno siguiente al del incidente")
    class Tipo2 {

        @Test
        @DisplayName("Averia en el turno de manana: vuelve al cerrar el turno de tarde")
        void averiaEnLaManana() {
            // 10:00 cae en el turno 07:00-15:00; el siguiente cierra a las 23:00 del mismo dia.
            assertEquals(minuto(1, 23, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(1, 10, 0)));
            // El limite inferior del turno, 07:00 en punto, da el mismo resultado.
            assertEquals(minuto(1, 23, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(1, 7, 0)));
        }

        @Test
        @DisplayName("Averia en el turno de tarde: vuelve al cerrar el turno de noche, ya el dia siguiente")
        void averiaEnLaTarde() {
            // 16:00 cae en 15:00-23:00; el siguiente es el nocturno, que cierra a las 07:00.
            assertEquals(minuto(2, 7, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(1, 16, 0)));
        }

        @Test
        @DisplayName("Averia en el turno de noche, antes y despues de la medianoche")
        void averiaEnLaNoche() {
            // 23:30 del dia 1 esta en el turno que arranco a las 23:00 del dia 1; el siguiente
            // es el de manana del dia 2, que cierra a las 15:00 del dia 2.
            assertEquals(minuto(2, 15, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(1, 23, 30)));
            // 02:00 del dia 2 pertenece al mismo turno nocturno, de modo que da el mismo instante.
            assertEquals(minuto(2, 15, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(2, 2, 0)));
            // La medianoche exacta tambien.
            assertEquals(minuto(2, 15, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(2, 0, 0)));
        }

        @Test
        @DisplayName("Permanece cuatro horas en el lugar y se traslada al almacen central")
        void permanenciaYTraslado() {
            assertTrue(TipoAveria.TIPO_2.trasladaAlmacenCentral());
            assertEquals(TipoAveria.MINUTOS_EN_EL_LUGAR, TipoAveria.TIPO_2.minutosEnElLugar());
        }
    }

    @Nested
    @DisplayName("Tipo 3, mayor: dos dias de taller y reingreso en el turno de 15:00 a 23:00")
    class Tipo3 {

        @Test
        @DisplayName("Averia antes de las 15:00: reingresa a las 15:00 del segundo dia posterior")
        void averiaTemprana() {
            // 10:00 del dia 1 mas 48 horas son las 10:00 del dia 3; el primer inicio de tarde
            // posterior o igual es las 15:00 de ese mismo dia 3.
            assertEquals(minuto(3, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 10, 0)));
        }

        @Test
        @DisplayName("Averia a las 15:00 en punto: reingresa exactamente dos dias despues")
        void averiaEnElBordeDeLaTarde() {
            assertEquals(minuto(3, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 15, 0)));
        }

        @Test
        @DisplayName("Averia despues de las 15:00: hay que esperar al dia siguiente")
        void averiaTardia() {
            // 16:00 del dia 1 mas 48 horas son las 16:00 del dia 3, ya pasado el inicio de la
            // tarde, de modo que reingresa a las 15:00 del dia 4.
            assertEquals(minuto(4, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 16, 0)));
            // 23:30 del dia 1 mas 48 horas son las 23:30 del dia 3: tambien pasa al dia 4.
            assertEquals(minuto(4, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 23, 30)));
        }

        @Test
        @DisplayName("Averia de madrugada: los dos dias se cuentan desde el instante, no desde el turno")
        void averiaDeMadrugada() {
            // 02:00 del dia 2 mas 48 horas son las 02:00 del dia 4, y el reingreso es a las
            // 15:00 de ese dia 4.
            assertEquals(minuto(4, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(2, 2, 0)));
        }

        @Test
        @DisplayName("Nunca reincorpora antes de las 48 horas de taller")
        void nuncaAntesDeLosDosDias() {
            for (int m = 0; m < 4 * 1440; m += 37) {
                long reingreso = TipoAveria.TIPO_3.minutoReincorporacion(m);
                assertTrue(reingreso >= m + 2 * 1440L,
                        "el reingreso del minuto " + m + " ocurre antes de las 48 horas: " + reingreso);
                assertEquals(Turno.TARDE, Turno.enMinuto(reingreso),
                        "el reingreso del minuto " + m + " debe caer en el turno de tarde");
                assertEquals(reingreso, Turno.inicioDelTurno(reingreso),
                        "el reingreso debe coincidir con el arranque del turno de tarde");
            }
        }

        @Test
        @DisplayName("Permanece cuatro horas en el lugar y se traslada al almacen central")
        void permanenciaYTraslado() {
            assertTrue(TipoAveria.TIPO_3.trasladaAlmacenCentral());
            assertEquals(TipoAveria.MINUTOS_EN_EL_LUGAR, TipoAveria.TIPO_3.minutosEnElLugar());
        }
    }

    @Test
    @DisplayName("El codigo numerico resuelve el tipo y rechaza cualquier otro valor")
    void resolucionPorCodigo() {
        assertEquals(TipoAveria.TIPO_1, TipoAveria.porCodigo(1));
        assertEquals(TipoAveria.TIPO_2, TipoAveria.porCodigo(2));
        assertEquals(TipoAveria.TIPO_3, TipoAveria.porCodigo(3));
        assertThrows(IllegalArgumentException.class, () -> TipoAveria.porCodigo(0));
        assertThrows(IllegalArgumentException.class, () -> TipoAveria.porCodigo(4));
    }

    @Test
    @DisplayName("El traslado al almacen central ocurre al cumplirse la permanencia en el lugar")
    void trasladoAlCumplirLaPermanencia() {
        Averia menor = new Averia("TB03", TipoAveria.TIPO_1, minuto(1, 10, 0), Ciudad.nodo(10, 10));
        Averia intermedia = new Averia("TM02", TipoAveria.TIPO_2, minuto(1, 10, 0), Ciudad.nodo(10, 10));
        assertEquals(minuto(1, 12, 0), menor.minutoTrasladoAlmacenCentral());
        assertEquals(minuto(1, 14, 0), intermedia.minutoTrasladoAlmacenCentral());
        assertEquals(minuto(1, 23, 0), intermedia.minutoReincorporacion());
    }
}
