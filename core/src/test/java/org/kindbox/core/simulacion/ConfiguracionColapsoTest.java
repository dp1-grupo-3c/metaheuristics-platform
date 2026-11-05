package org.kindbox.core.simulacion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El servicio y los experimentos construyen el colapso con la misma fabrica: lo que cada uno
 * deja elegir (fotografias, averias, arranque) no toca el K, el reloj ni el horizonte.
 */
class ConfiguracionColapsoTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("La fabrica corta es la larga con los valores por defecto")
    void laFabricaCortaDelegaEnLaLarga() {
        assertEquals(ConfiguracionEscenario.colapso(DIA, 30, "HGS", 7L, 15, true, 0.02, false),
                ConfiguracionEscenario.colapso(DIA, 30, "HGS", 7L));
    }

    @Test
    @DisplayName("Los parametros elegibles no cambian el K, el reloj ni el presupuesto")
    void losParametrosElegiblesNoCambianLoComparable() {
        ConfiguracionEscenario experimentos = ConfiguracionEscenario.colapso(DIA, 30, "HGS", 7L);
        ConfiguracionEscenario servicio = ConfiguracionEscenario.colapso(DIA, 30, "HGS", 7L, 5, false, 0.0, true);

        assertEquals(ConfiguracionEscenario.FACTOR_COLAPSO, servicio.factorAceleracion());
        assertEquals(experimentos.factorAceleracion(), servicio.factorAceleracion());
        assertEquals(experimentos.modoReloj(), servicio.modoReloj());
        assertEquals(experimentos.ultimoDia(), servicio.ultimoDia());
        assertEquals(experimentos.presupuestoPlanificacion().milisegundosTotales(),
                servicio.presupuestoPlanificacion().milisegundosTotales());
    }
}
