package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Test
    void elMinutoInicialArrancaEnCeroYSeConservaAlDerivar() {
        ConfiguracionEscenario base = ConfiguracionEscenario.diaADia(LocalDate.of(2026, 9, 1), 30, "ALNS", 7L);
        assertEquals(0, base.minutoInicial(), "el constructor de doce argumentos arranca a las 00:00");
        ConfiguracionEscenario media = base.conMinutoInicial(630);
        assertEquals(630, media.minutoInicial());
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 30), media.fechaHoraInicio());
        assertEquals(base.ultimoDia(), media.ultimoDia());
        assertEquals(630, media.conArranqueDesdePlanVigente(!media.arranqueDesdePlanVigente()).minutoInicial());
        assertThrows(IllegalArgumentException.class, () -> base.conMinutoInicial(-1));
        assertThrows(IllegalArgumentException.class, () -> base.conMinutoInicial(1440));
    }
}
