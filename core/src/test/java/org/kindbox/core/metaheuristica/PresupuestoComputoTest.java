package org.kindbox.core.metaheuristica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.problema.ValorObjetivo;

/**
 * Presupuesto de computo del apartado 2.3 del ISA en sus dos modos.
 *
 * <p>El modo por iteraciones existe para que una corrida sea reproducible bit a bit: su
 * agotamiento y su fraccion consumida tienen que ser funciones puras del contador, y el
 * contador tiene que agotarlo exactamente en el tope, ni una iteracion antes ni una despues.
 * El modo por reloj, el de operacion, no debe enterarse del contador.</p>
 */
class PresupuestoComputoTest {

    @Test
    @DisplayName("El presupuesto por iteraciones se agota exactamente en N")
    void seAgotaExactamenteEnN() {
        final long n = 7L;
        PresupuestoComputo presupuesto = PresupuestoComputo.deIteraciones(n).arrancar();
        assertTrue(presupuesto.porIteraciones());
        assertEquals(n, presupuesto.limiteIteraciones());
        for (long k = 0; k < n; k++) {
            assertFalse(presupuesto.agotado(), "agotado antes de tiempo con " + k + " iteraciones");
            assertEquals((double) k / n, presupuesto.fraccionConsumida());
            presupuesto.contarIteracion();
        }
        assertTrue(presupuesto.agotado());
        assertEquals(n, presupuesto.iteraciones());
        assertEquals(1.0, presupuesto.fraccionConsumida());

        presupuesto.contarIteracion();
        assertTrue(presupuesto.agotado());
        assertEquals(1.0, presupuesto.fraccionConsumida(), "la fraccion se acota a uno");
    }

    @Test
    @DisplayName("En modo por iteraciones el reloj no agota ni consume presupuesto")
    void porIteracionesNoMiraElReloj() throws InterruptedException {
        PresupuestoComputo presupuesto = PresupuestoComputo.deIteraciones(3L).arrancar();
        Thread.sleep(15L);
        assertFalse(presupuesto.agotado());
        assertEquals(0.0, presupuesto.fraccionConsumida());
        assertEquals(0L, presupuesto.milisegundosTotales());
        assertTrue(presupuesto.milisegundosTranscurridos() >= 10L, "el reloj sigue siendo informativo");
    }

    @Test
    @DisplayName("La cancelacion agota el presupuesto y arrancar lo restituye")
    void cancelarYArrancar() {
        PresupuestoComputo presupuesto = PresupuestoComputo.deIteraciones(10L).arrancar();
        presupuesto.contarIteracion();
        presupuesto.contarIteracion();
        presupuesto.cancelar();
        assertTrue(presupuesto.agotado());

        presupuesto.arrancar();
        assertFalse(presupuesto.agotado());
        assertEquals(0L, presupuesto.iteraciones());
        assertEquals(0.0, presupuesto.fraccionConsumida());
    }

    @Test
    @DisplayName("Un tope de iteraciones no positivo se rechaza")
    void rechazaTopeNoPositivo() {
        assertThrows(IllegalArgumentException.class, () -> PresupuestoComputo.deIteraciones(0L));
        assertThrows(IllegalArgumentException.class, () -> PresupuestoComputo.deIteracionesConPerfil(-5L));
    }

    @Test
    @DisplayName("Con presupuesto por iteraciones cada hito del perfil cae en su iteracion exacta")
    void hitosDelPerfilPorIteraciones() {
        assertNull(PresupuestoComputo.deIteraciones(100L).perfil());
        PresupuestoComputo presupuesto = PresupuestoComputo.deIteracionesConPerfil(100L).arrancar();
        assertNotNull(presupuesto.perfil());
        final ValorObjetivo valor = ValorObjetivo.cero();
        for (int k = 0; k < 100; k++) {
            presupuesto.contarIteracion();
            presupuesto.muestrear(() -> valor);
        }
        List<PerfilConvergencia.Medicion> mediciones = presupuesto.perfil().mediciones();
        assertEquals(PerfilConvergencia.HITOS.length, mediciones.size());
        for (int i = 0; i < mediciones.size(); i++) {
            assertEquals(PerfilConvergencia.HITOS[i], mediciones.get(i).fraccionPresupuesto());
            assertEquals(Math.round(PerfilConvergencia.HITOS[i] * 100.0), mediciones.get(i).iteraciones(),
                    "hito " + PerfilConvergencia.HITOS[i]);
        }
    }

    @Test
    @DisplayName("El modo por reloj ignora el contador de iteraciones")
    void porRelojIgnoraElContador() {
        PresupuestoComputo presupuesto = PresupuestoComputo.deMilisegundos(60_000L).arrancar();
        assertFalse(presupuesto.porIteraciones());
        assertEquals(0L, presupuesto.limiteIteraciones());
        assertEquals(60_000L, presupuesto.milisegundosTotales());
        for (int k = 0; k < 1_000_000; k++) {
            presupuesto.contarIteracion();
        }
        assertFalse(presupuesto.agotado());
        assertTrue(presupuesto.fraccionConsumida() < 0.5);
    }
}
