package org.kindbox.core.operacion;

import java.util.Map;
import org.kindbox.core.modelo.TipoUnidad;

public record SolicitudParametrosOperacion(String solicitudId, Map<TipoUnidad, Double> velocidades,
                                            Integer ambar, Integer verde) {
    public SolicitudParametrosOperacion {
        solicitudId = SolicitudPedidoOperacion.texto(solicitudId, "solicitudId", 100);
        velocidades = velocidades == null ? Map.of() : Map.copyOf(velocidades);
        if ((ambar == null) != (verde == null) || ambar != null && (ambar < 0 || verde <= ambar)) {
            throw new IllegalArgumentException("Los umbrales del semaforo son invalidos");
        }
        for (var entrada : velocidades.entrySet()) {
            Double velocidad = entrada.getValue();
            if (velocidad == null || !Double.isFinite(velocidad) || velocidad <= 0) {
                throw new IllegalArgumentException("La velocidad debe ser positiva y finita");
            }
        }
        if (velocidades.isEmpty() && ambar == null) {
            throw new IllegalArgumentException("Indique al menos un parametro");
        }
    }
}
