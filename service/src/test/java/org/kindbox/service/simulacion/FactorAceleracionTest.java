package org.kindbox.service.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.TipoEscenario;

/**
 * El factor K del colapso no depende del rango de fechas ni de la duracion pedida.
 *
 * <p>Con el reloj libre, K solo fija el presupuesto de cada ejecucion del planificador. Se
 * derivaba de los dias del rango, de modo que pedir un ano en lugar de cinco dias dejaba al
 * algoritmo con unos 60 ms por busqueda en lugar de 4,5 s y adelantaba el colapso.</p>
 */
class FactorAceleracionTest {

    @Test
    @DisplayName("El colapso usa el mismo K para cualquier rango y duracion")
    void elColapsoNoDependeDelRango() {
        assertEquals(240.0, ServicioSimulacion.factorAceleracion(TipoEscenario.COLAPSO, 5, 30));
        assertEquals(240.0, ServicioSimulacion.factorAceleracion(TipoEscenario.COLAPSO, 365, 30));
        assertEquals(240.0, ServicioSimulacion.factorAceleracion(TipoEscenario.COLAPSO, 31, 60));
    }

    @Test
    @DisplayName("El presupuesto por busqueda del colapso es el de la 5D en 30 minutos")
    void elPresupuestoDelColapsoEsElDeLa5D() {
        LocalDate dia = LocalDate.of(2026, 9, 1);
        var cincoDias = ConfiguracionEscenario.simulacion5D(dia, 30, 30, "HGS", 1L);
        var colapso = ConfiguracionEscenario.colapso(dia, 30, "HGS", 1L);
        assertEquals(cincoDias.presupuestoPlanificacion().milisegundosTotales(),
                colapso.presupuestoPlanificacion().milisegundosTotales());
    }

    @Test
    @DisplayName("La 5D y el dia a dia conservan su K")
    void losDemasEscenariosNoCambian() {
        assertEquals(240.0, ServicioSimulacion.factorAceleracion(TipoEscenario.SIMULACION_5D, 5, 30));
        assertEquals(120.0, ServicioSimulacion.factorAceleracion(TipoEscenario.SIMULACION_5D, 5, 60));
        assertEquals(1.0, ServicioSimulacion.factorAceleracion(TipoEscenario.DIA_A_DIA, 1, 30));
    }
}
