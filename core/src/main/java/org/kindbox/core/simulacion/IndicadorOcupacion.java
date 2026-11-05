package org.kindbox.core.simulacion;

import java.util.List;
import org.kindbox.core.modelo.ParametrosOperacion;

// Ocupacion de un conjunto, la flota o los almacenes, en numero, porcentaje y color.
public record IndicadorOcupacion(int ocupado, int capacidad, double porcentaje, ColorSemaforo color) {

    // Carga a bordo de toda la flota sobre su capacidad, con los umbrales de ocupacion.
    public static IndicadorOcupacion deFlota(List<VistaUnidad> unidades, ParametrosOperacion.Instantanea parametros) {
        int carga = 0;
        int capacidad = 0;
        for (VistaUnidad u : unidades) {
            carga += u.cargaABordo();
            capacidad += u.capacidad();
        }
        return new IndicadorOcupacion(carga, capacidad, porcentaje(carga, capacidad),
                ColorSemaforo.deOcupacion(carga, capacidad, parametros));
    }

    // Stock de los almacenes intermedios sobre su capacidad; el central es ilimitado y no cuenta.
    public static IndicadorOcupacion deAlmacenes(List<VistaAlmacen> almacenes,
                                                 ParametrosOperacion.Instantanea parametros) {
        int disponible = 0;
        int capacidad = 0;
        int intermedios = 0;
        for (VistaAlmacen a : almacenes) {
            if (a.central()) {
                continue;
            }
            disponible += Math.max(0, a.disponible());
            capacidad += Math.max(0, a.capacidad());
            intermedios++;
        }
        return new IndicadorOcupacion(disponible, capacidad, porcentaje(disponible, capacidad),
                ColorSemaforo.deInventarioConjunto(disponible, intermedios, parametros));
    }

    // Porcentaje con un decimal, para mostrarlo tal cual.
    private static double porcentaje(int ocupado, int capacidad) {
        return capacidad <= 0 ? 0.0 : Math.round(1000.0 * ocupado / capacidad) / 10.0;
    }
}
