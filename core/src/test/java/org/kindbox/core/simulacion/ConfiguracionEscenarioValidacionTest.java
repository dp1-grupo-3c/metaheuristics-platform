package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConfiguracionEscenarioValidacionTest {

    @Test
    void rechazaFechasNulas() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracionEscenario(
                TipoEscenario.DIA_A_DIA, null, LocalDate.of(2026, 1, 1),
                30, 1.0, ModoReloj.LIBRE, "ALNS", 1L, 1, false, 0.0, false));
    }

    @Test
    void rechazaRangoInvertido() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracionEscenario(
                TipoEscenario.DIA_A_DIA, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1),
                30, 1.0, ModoReloj.LIBRE, "ALNS", 1L, 1, false, 0.0, false));
    }

    @Test
    void rechazaAlgoritmoVacio() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracionEscenario(
                TipoEscenario.DIA_A_DIA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1),
                30, 1.0, ModoReloj.LIBRE, " ", 1L, 1, false, 0.0, false));
    }
}
