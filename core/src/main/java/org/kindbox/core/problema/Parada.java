package org.kindbox.core.problema;

import org.kindbox.core.modelo.Ciudad;

/**
 * Parada de una ruta. La representacion sigue el apartado 7.3.1 del ISA: cada parada
 * lleva su tipo, el nodo en que ocurre y la cantidad de unidades involucrada.
 *
 * @param tipo           naturaleza de la parada
 * @param nodo           nodo de la reticula
 * @param idPedido       identificador del pedido atendido, o {@code -1} si no aplica
 * @param idAlmacen      identificador del almacen, o {@code -1} si no aplica
 * @param cantidad       unidades entregadas o recogidas, {@code 0} si no aplica
 * @param minutoLlegada  instante de llegada, en minutos desde el inicio del escenario
 * @param minutoSalida   instante de salida, ya descontado el tiempo de servicio
 * @param kmDesdeAnterior kilometros recorridos desde la parada anterior
 */
public record Parada(
        TipoParada tipo,
        int nodo,
        int idPedido,
        int idAlmacen,
        int cantidad,
        long minutoLlegada,
        long minutoSalida,
        int kmDesdeAnterior) {

    /** Parada de entrega a un destinatario. */
    public static Parada entrega(int nodo, int idPedido, int cantidad,
                                 long minutoLlegada, long minutoSalida, int kmDesdeAnterior) {
        return new Parada(TipoParada.ENTREGA, nodo, idPedido, -1, cantidad,
                minutoLlegada, minutoSalida, kmDesdeAnterior);
    }

    /** Parada de abastecimiento en almacen. */
    public static Parada abastecimiento(int nodo, int idAlmacen, int cantidad,
                                        long minutoLlegada, long minutoSalida, int kmDesdeAnterior) {
        return new Parada(TipoParada.ABASTECIMIENTO, nodo, -1, idAlmacen, cantidad,
                minutoLlegada, minutoSalida, kmDesdeAnterior);
    }

    /** Pausa de alimentacion, que ocurre en el nodo en que se encuentra la unidad. */
    public static Parada alimentacion(int nodo, long minutoLlegada, long minutoSalida) {
        return new Parada(TipoParada.ALIMENTACION, nodo, -1, -1, 0,
                minutoLlegada, minutoSalida, 0);
    }

    /** Duracion del servicio en la parada, en minutos. */
    public long duracionServicio() {
        return minutoSalida - minutoLlegada;
    }

    @Override
    public String toString() {
        return tipo + Ciudad.texto(nodo) + "@" + minutoLlegada
                + (idPedido >= 0 ? " p" + idPedido + "x" + cantidad : "")
                + (idAlmacen >= 0 ? " a" + idAlmacen + "x" + cantidad : "");
    }
}
