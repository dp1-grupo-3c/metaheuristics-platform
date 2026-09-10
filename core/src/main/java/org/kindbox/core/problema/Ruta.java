package org.kindbox.core.problema;

import java.util.Collections;
import java.util.List;
import org.kindbox.core.modelo.TipoUnidad;

/**
 * Ruta asignada a una unidad de transporte durante un turno.
 *
 * <p>Una ruta arranca en el nodo en que se encuentra la unidad, encadena paradas de
 * abastecimiento, entrega y alimentacion, y no puede extenderse mas alla del cierre
 * del turno de la unidad (restriccion dura del apartado 2.6 del ISA).</p>
 *
 * @param codigoUnidad codigo TTNN de la unidad
 * @param tipoUnidad   tipo de la unidad
 * @param nodoInicial  nodo desde el que arranca la ruta
 * @param minutoInicio instante de arranque
 * @param paradas      secuencia ordenada de paradas
 */
public record Ruta(
        String codigoUnidad,
        TipoUnidad tipoUnidad,
        int nodoInicial,
        long minutoInicio,
        List<Parada> paradas) {

    public Ruta {
        paradas = List.copyOf(paradas);
    }

    /** Ruta sin paradas para una unidad que permanece en su posicion. */
    public static Ruta vacia(String codigoUnidad, TipoUnidad tipoUnidad, int nodoInicial, long minutoInicio) {
        return new Ruta(codigoUnidad, tipoUnidad, nodoInicial, minutoInicio, Collections.emptyList());
    }

    /** Indica si la ruta no contiene ninguna entrega. */
    public boolean sinEntregas() {
        for (Parada p : paradas) {
            if (p.tipo() == TipoParada.ENTREGA) {
                return false;
            }
        }
        return true;
    }

    /** Kilometros totales recorridos por la ruta. */
    public int kilometros() {
        int km = 0;
        for (Parada p : paradas) {
            km += p.kmDesdeAnterior();
        }
        return km;
    }

    /** Costo de operacion de la ruta, en soles. */
    public double costo() {
        return kilometros() * tipoUnidad.costoPorKm();
    }

    /** Numero de paradas de entrega. */
    public int cantidadEntregas() {
        int n = 0;
        for (Parada p : paradas) {
            if (p.tipo() == TipoParada.ENTREGA) {
                n++;
            }
        }
        return n;
    }

    /** Unidades del producto P entregadas por la ruta. */
    public int unidadesEntregadas() {
        int q = 0;
        for (Parada p : paradas) {
            if (p.tipo() == TipoParada.ENTREGA) {
                q += p.cantidad();
            }
        }
        return q;
    }

    /** Instante en que la ruta termina. Coincide con el inicio si no hay paradas. */
    public long minutoFin() {
        return paradas.isEmpty() ? minutoInicio : paradas.get(paradas.size() - 1).minutoSalida();
    }

    /** Indica si la ruta incluye la pausa de alimentacion. */
    public boolean incluyeAlimentacion() {
        for (Parada p : paradas) {
            if (p.tipo() == TipoParada.ALIMENTACION) {
                return true;
            }
        }
        return false;
    }
}
