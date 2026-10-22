package org.kindbox.core.evaluacion;

import java.util.Arrays;
import org.kindbox.core.problema.*;

/** Criterio secundario: riesgo de llegar con menos margen del deseado, sin alterar H/U/costo. */
public final class HolguraTemporal {
    public static final String PROPIEDAD_MARGEN = "alns.holguraObjetivoMinutos";
    private HolguraTemporal() {}

    /** Cero desactiva la preferencia. Se captura una vez al construir cada estado ALNS. */
    public static int margenConfigurado() {
        int margen = Integer.parseInt(System.getProperty(PROPIEDAD_MARGEN, "30"));
        if (margen < 0 || margen > 1440) {
            throw new IllegalArgumentException(PROPIEDAD_MARGEN + " debe estar entre 0 y 1440");
        }
        return margen;
    }

    /** Deficit cuadratico acotado: los planes factibles tienen holgura no negativa. */
    public static long penalizacion(long holgura, int margen) {
        long deficit = Math.max(0L, margen - Math.max(0L, holgura));
        return deficit * deficit;
    }

    /**
     * Riesgo por pedido COMPLETAMENTE asignado; la ultima parte determina su llegada.
     * Los pedidos con remanente siguen siendo responsabilidad de H y U.
     */
    public static long riesgo(InstanciaPlanificacion instancia, Solucion solucion, int margen) {
        int[] cantidad = new int[instancia.cantidadPedidos()];
        long[] ultima = new long[cantidad.length];
        Arrays.fill(ultima, Long.MIN_VALUE);
        for (Ruta ruta : solucion.rutas()) for (Parada parada : ruta.paradas()) {
            if (parada.tipo() != TipoParada.ENTREGA) continue;
            int p = instancia.indiceDePedido(parada.idPedido());
            if (p < 0) continue;
            cantidad[p] += parada.cantidad();
            ultima[p] = Math.max(ultima[p], parada.minutoLlegada());
        }
        long riesgo = 0;
        for (int p = 0; p < cantidad.length; p++) {
            if (cantidad[p] >= instancia.pedidoCantidad(p) && ultima[p] != Long.MIN_VALUE) {
                riesgo += penalizacion(instancia.pedidoMinutoLimite(p) - ultima[p], margen);
            }
        }
        return riesgo;
    }
}
