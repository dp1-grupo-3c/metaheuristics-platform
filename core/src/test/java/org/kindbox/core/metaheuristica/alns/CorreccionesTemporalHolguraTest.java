package org.kindbox.core.metaheuristica.alns;

import org.junit.jupiter.api.Test;
import org.kindbox.core.evaluacion.HolguraTemporal;

/** Ejecuta las regresiones tambien desde Maven/Surefire. */
class CorreccionesTemporalHolguraTest {
    @Test
    void conservaLlegadasYDesempataPorHolgura() throws Exception {
        String anterior = System.getProperty(HolguraTemporal.PROPIEDAD_MARGEN);
        try {
            VerificarCorrecciones.main(new String[0]);
        } finally {
            if (anterior == null) System.clearProperty(HolguraTemporal.PROPIEDAD_MARGEN);
            else System.setProperty(HolguraTemporal.PROPIEDAD_MARGEN, anterior);
        }
    }
}
