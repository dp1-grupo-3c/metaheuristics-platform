package org.kindbox.core.grafo;

/**
 * Matriz de distancias entre los puntos relevantes de una iteracion de planificacion,
 * conforme al apartado 10 del ISA.
 *
 * <p>Los puntos relevantes son los almacenes, los destinos de los pedidos pendientes y
 * las posiciones actuales de las unidades. La matriz se recalcula ante la activacion o
 * desactivacion de un bloqueo. Como todas las calles son de doble sentido, la matriz es
 * simetrica en distancia.</p>
 *
 * <p>Las distancias estan en kilometros enteros, porque la reticula tiene nodos cada
 * kilometro y no existen calles diagonales ni curvas.</p>
 */
public interface MatrizDistancias {

    /** Valor devuelto por {@link #km(int, int)} cuando no existe camino entre dos puntos. */
    int INALCANZABLE = Integer.MAX_VALUE / 4;

    /** Numero de puntos indexados por la matriz. */
    int cantidadPuntos();

    /** Nodo de la reticula que corresponde al punto dado. */
    int nodo(int punto);

    /** Distancia en kilometros del camino mas corto entre dos puntos, esquivando los bloqueos vigentes. */
    int km(int puntoOrigen, int puntoDestino);

    /** Indica si existe un camino entre ambos puntos. */
    default boolean alcanzable(int puntoOrigen, int puntoDestino) {
        return km(puntoOrigen, puntoDestino) < INALCANZABLE;
    }

    /**
     * Puntos mas cercanos al dado, en orden de distancia creciente y sin incluirlo.
     *
     * <p>Alimenta el parametro de granularidad del vecindario de la busqueda local de HGS
     * (apartado 6.3.3) y el operador de remocion por afinidad de ALNS (apartado 7.3.2).
     * El arreglo devuelto no debe modificarse.</p>
     */
    int[] vecinosCercanos(int punto);

    /**
     * Camino nodo a nodo entre dos puntos, util para dibujar el trazo de la ruta en el
     * visualizador. Devuelve un arreglo de nodos que arranca en el origen y termina en el
     * destino, o un arreglo vacio si no hay camino.
     */
    int[] camino(int puntoOrigen, int puntoDestino);
}
