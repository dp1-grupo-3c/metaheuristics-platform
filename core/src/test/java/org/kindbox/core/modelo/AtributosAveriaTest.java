package org.kindbox.core.modelo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AtributosAveriaTest {

    // Minuto absoluto de la hora indicada, con el dia 1 como primero.
    private static long minuto(int dia, int hora, int minuto) {
        return (dia - 1) * 1440L + hora * 60L + minuto;
    }

    @AfterEach
    void restablecer() {
        TipoAveria.restablecerAtributos();
    }

    @Test
    @DisplayName("Los atributos por defecto son los del enunciado")
    void valoresPorDefecto() {
        assertEquals(new AtributosAveria(120, 1, 2), TipoAveria.TIPO_1.atributosPorDefecto());
        assertEquals(new AtributosAveria(240, 1, 2), TipoAveria.TIPO_2.atributosPorDefecto());
        assertEquals(new AtributosAveria(240, 1, 2), TipoAveria.TIPO_3.atributosPorDefecto());
        for (TipoAveria tipo : TipoAveria.values()) {
            assertEquals(tipo.atributosPorDefecto(), tipo.atributos());
        }
        assertEquals(120, TipoAveria.TIPO_1.minutosEnElLugar());
        assertEquals(240, TipoAveria.TIPO_2.minutosEnElLugar());
        assertEquals(240, TipoAveria.TIPO_3.minutosEnElLugar());
    }

    @Test
    @DisplayName("El registro acepta los extremos de cada rango y rechaza lo que queda fuera")
    void rangos() {
        assertDoesNotThrow(() -> new AtributosAveria(1, 1, 1));
        assertDoesNotThrow(() -> new AtributosAveria(1440, 9, 14));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(1441, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(60, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(60, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(60, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AtributosAveria(60, 1, 15));
    }

    @Test
    @DisplayName("Tipo 1: se reincorpora al cumplir la permanencia configurada")
    void tipo1Configurado() {
        TipoAveria.TIPO_1.atributos(new AtributosAveria(90, 1, 2));
        assertEquals(minuto(1, 11, 30), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 10, 0)));
        assertEquals(minuto(2, 0, 30), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 23, 0)));
        assertEquals(90, TipoAveria.TIPO_1.minutosEnElLugar());

        // Sin traslado al central admite permanencias de hasta un dia.
        TipoAveria.TIPO_1.atributos(new AtributosAveria(1440, 1, 2));
        assertEquals(minuto(2, 10, 0), TipoAveria.TIPO_1.minutoReincorporacion(minuto(1, 10, 0)));
    }

    @Test
    @DisplayName("Tipo 2: cierre del turno del incidente mas los turnos configurados")
    void tipo2Configurado() {
        TipoAveria.TIPO_2.atributos(new AtributosAveria(300, 3, 2));
        // 10:00 cae en el turno que cierra a las 15:00; tres turnos mas son las 15:00 del dia 2.
        assertEquals(minuto(2, 15, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(1, 10, 0)));
        // 02:00 del dia 2 cae en el turno que cierra a las 07:00; dos turnos mas son las 23:00.
        TipoAveria.TIPO_2.atributos(new AtributosAveria(240, 2, 2));
        assertEquals(minuto(2, 23, 0), TipoAveria.TIPO_2.minutoReincorporacion(minuto(2, 2, 0)));

        Averia averia = new Averia("TM01", TipoAveria.TIPO_2, minuto(1, 10, 0), Ciudad.nodo(5, 5));
        assertEquals(minuto(1, 14, 0), averia.minutoTrasladoAlmacenCentral());
    }

    @Test
    @DisplayName("Tipo 3: dias de mantenimiento configurados y reingreso a las 15:00")
    void tipo3Configurado() {
        TipoAveria.TIPO_3.atributos(new AtributosAveria(240, 1, 5));
        assertEquals(minuto(6, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 10, 0)));
        assertEquals(minuto(7, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 16, 0)));

        TipoAveria.TIPO_3.atributos(new AtributosAveria(240, 1, 1));
        assertEquals(minuto(2, 15, 0), TipoAveria.TIPO_3.minutoReincorporacion(minuto(1, 15, 0)));
    }

    @Test
    @DisplayName("Con traslado al central la permanencia no pasa de un turno y el rechazo no cambia nada")
    void topeDePermanenciaConTraslado() {
        AtributosAveria largos = new AtributosAveria(481, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> TipoAveria.TIPO_2.atributos(largos));
        assertThrows(IllegalArgumentException.class, () -> TipoAveria.TIPO_3.atributos(largos));
        assertThrows(IllegalArgumentException.class, () -> TipoAveria.TIPO_2.atributos(null));
        assertEquals(TipoAveria.TIPO_2.atributosPorDefecto(), TipoAveria.TIPO_2.atributos());
        assertEquals(TipoAveria.TIPO_3.atributosPorDefecto(), TipoAveria.TIPO_3.atributos());
        assertDoesNotThrow(() -> TipoAveria.TIPO_2.atributos(new AtributosAveria(480, 1, 2)));
    }

    @Test
    @DisplayName("En el tipo 2 el traslado al central siempre precede al reingreso")
    void trasladoAntesDelReingreso() {
        for (int turnos = 1; turnos <= AtributosAveria.TURNOS_MAXIMO; turnos++) {
            TipoAveria.TIPO_2.atributos(new AtributosAveria(480, turnos, 2));
            for (long m = 0; m < 3 * 1440L; m += 23) {
                long traslado = m + TipoAveria.TIPO_2.minutosEnElLugar();
                assertTrue(traslado < TipoAveria.TIPO_2.minutoReincorporacion(m),
                        "turnos=" + turnos + " minuto=" + m);
            }
        }
    }

    @Test
    @DisplayName("Restablecer devuelve todos los tipos a sus valores por defecto")
    void restablecerTodos() {
        TipoAveria.TIPO_1.atributos(new AtributosAveria(30, 1, 2));
        TipoAveria.TIPO_3.atributos(new AtributosAveria(100, 4, 9));
        TipoAveria.restablecerAtributos();
        for (TipoAveria tipo : TipoAveria.values()) {
            assertSame(tipo.atributosPorDefecto(), tipo.atributos());
        }
    }

    @Test
    @DisplayName("ParametrosOperacion aplica el cambio y sube la version solo si es valido")
    void cambioDesdeParametrosOperacion() {
        ParametrosOperacion parametros = new ParametrosOperacion();
        long antes = parametros.version();
        AtributosAveria nuevos = new AtributosAveria(200, 2, 3);
        parametros.atributosAveria(TipoAveria.TIPO_2, nuevos);
        assertEquals(nuevos, TipoAveria.TIPO_2.atributos());
        assertEquals(antes + 1, parametros.version());

        assertThrows(IllegalArgumentException.class,
                () -> parametros.atributosAveria(TipoAveria.TIPO_3, new AtributosAveria(600, 1, 2)));
        assertThrows(IllegalArgumentException.class, () -> parametros.atributosAveria(null, nuevos));
        assertEquals(antes + 1, parametros.version());
        assertEquals(TipoAveria.TIPO_3.atributosPorDefecto(), TipoAveria.TIPO_3.atributos());
    }
}
