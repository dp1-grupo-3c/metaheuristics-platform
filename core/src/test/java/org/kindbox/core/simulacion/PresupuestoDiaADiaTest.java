package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PresupuestoDiaADiaTest {
    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    @Test
    void diaADiaMantieneRelojRealSinBuscarDuranteDieciochoMinutos() {
        var configuracion = ConfiguracionEscenario.diaADia(DIA, 30, "ALNS", 1);
        assertEquals(1.0, configuracion.factorAceleracion());
        assertEquals(ModoReloj.ACOMPASADO, configuracion.modoReloj());
        assertEquals(60_000.0, configuracion.milisegundosPorMinutoSimulado());
        assertEquals(18_000L, configuracion.presupuestoPlanificacion().milisegundosTotales());
        assertNotNull(configuracion.presupuestoPlanificacion().perfil());
    }

    @Test
    void conservaLosPresupuestosExperimentalesDeCincoDiasYColapso() {
        assertEquals(4_500L, ConfiguracionEscenario.simulacion5D(DIA, 30, 30, "ALNS", 1)
                .presupuestoPlanificacion().milisegundosTotales());
        assertEquals(9_000L, ConfiguracionEscenario.simulacion5D(DIA, 60, 30, "HGS", 1)
                .presupuestoPlanificacion().milisegundosTotales());
        assertEquals(4_500L, ConfiguracionEscenario.colapso(DIA, 30, "ALNS", 1)
                .presupuestoPlanificacion().milisegundosTotales());
    }
}
