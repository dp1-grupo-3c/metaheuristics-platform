package org.kindbox.core.operacion;

import java.time.ZoneId;

public record ConfiguracionOperacion(ZoneId zonaHoraria, int saltoMinutos, String algoritmo,
                                     long semilla, long presupuestoPlanificacionMs) {
    public ConfiguracionOperacion {
        if (zonaHoraria == null || algoritmo == null || algoritmo.isBlank()
                || saltoMinutos < 1 || saltoMinutos > 1440
                || presupuestoPlanificacionMs < 50 || presupuestoPlanificacionMs > 18000) {
            throw new IllegalArgumentException("La configuracion operativa es invalida");
        }
    }

    public static ConfiguracionOperacion porDefecto() {
        return new ConfiguracionOperacion(ZoneId.of("America/Lima"), 30, "ALNS", 20262, 2000);
    }
}
