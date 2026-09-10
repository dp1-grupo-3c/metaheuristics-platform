package org.kindbox.core.grafo;

import java.util.Arrays;
import org.kindbox.core.modelo.Ciudad;

/**
 * Calculo de caminos minimos sobre la reticula de la ciudad esquivando los tramos
 * bloqueados. Es el motor sobre el que se construye la matriz de tiempos y distancias del
 * apartado 10 del ISA.
 *
 * <p>Todas las aristas de la reticula miden 1 Km y no hay diagonales ni curvas, de modo
 * que el grafo no tiene pesos: una busqueda en anchura visita los nodos en orden de
 * distancia creciente y entrega el camino minimo. No se usa Dijkstra ni A* con cola de
 * prioridad porque la cola costaria un factor logaritmico sin ganar nada, y porque la
 * matriz necesita las distancias de un nodo a <b>todos</b> los puntos relevantes: una sola
 * busqueda en anchura responde a la fila completa, mientras que A* tendria que repetirse
 * para cada par.</p>
 *
 * <p>Los arreglos de trabajo son campos de instancia y se reutilizan entre llamadas, para
 * no asignar memoria en el bucle de planificacion. Por eso la clase <b>no</b> es segura
 * para uso concurrente: cada hilo que planifique necesita su propio oraculo.</p>
 */
public final class OraculoDistancias {

    /** Mascara sin ninguna arista bloqueada, usada cuando el llamante no entrega una. */
    private static final boolean[] SIN_BLOQUEOS = new boolean[RegistroBloqueos.TOTAL_ARISTAS];
    private static final int[] SIN_CAMINO = new int[0];

    /** Cola de la busqueda en anchura. Cada nodo entra a lo sumo una vez. */
    private final int[] cola = new int[Ciudad.TOTAL_NODOS];

    private long nodosVisitados;
    private long busquedas;

    /**
     * Recorre la ciudad desde un nodo y llena la distancia y el predecesor de cada nodo
     * alcanzable, esquivando las aristas bloqueadas.
     *
     * <p>Los nodos que quedan aislados por los bloqueos reciben distancia
     * {@link MatrizDistancias#INALCANZABLE} y predecesor {@code -1}. Con las poligonales
     * abiertas del curso 1INF54 2026-2 eso no llega a ocurrir, pero el planificador no
     * puede confiar en esa propiedad del juego de datos.</p>
     *
     * @param nodoOrigen        nodo desde el que se mide
     * @param aristaBloqueada   mascara de {@link RegistroBloqueos#TOTAL_ARISTAS} posiciones,
     *                          o {@code null} si no hay ningun bloqueo vigente
     * @param distanciaSalida   arreglo de {@link Ciudad#TOTAL_NODOS} posiciones que recibe
     *                          la distancia en kilometros
     * @param predecesorSalida  arreglo de {@link Ciudad#TOTAL_NODOS} posiciones que recibe
     *                          el nodo anterior en el camino minimo
     */
    public void distanciasDesde(int nodoOrigen, boolean[] aristaBloqueada,
                                int[] distanciaSalida, int[] predecesorSalida) {
        if (nodoOrigen < 0 || nodoOrigen >= Ciudad.TOTAL_NODOS) {
            throw new IllegalArgumentException("Nodo de origen fuera de la ciudad: " + nodoOrigen);
        }
        if (distanciaSalida.length != Ciudad.TOTAL_NODOS || predecesorSalida.length != Ciudad.TOTAL_NODOS) {
            throw new IllegalArgumentException("Los arreglos de salida deben tener "
                    + Ciudad.TOTAL_NODOS + " posiciones");
        }
        boolean[] bloqueada = aristaBloqueada == null ? SIN_BLOQUEOS : aristaBloqueada;
        if (bloqueada.length != RegistroBloqueos.TOTAL_ARISTAS) {
            throw new IllegalArgumentException("La mascara de bloqueos debe tener "
                    + RegistroBloqueos.TOTAL_ARISTAS + " posiciones");
        }

        Arrays.fill(distanciaSalida, MatrizDistancias.INALCANZABLE);
        Arrays.fill(predecesorSalida, -1);
        distanciaSalida[nodoOrigen] = 0;
        cola[0] = nodoOrigen;
        int cabeza = 0;
        int fin = 1;

        while (cabeza < fin) {
            int nodo = cola[cabeza++];
            int distanciaVecino = distanciaSalida[nodo] + 1;
            int x = Ciudad.x(nodo);
            int y = Ciudad.y(nodo);
            int base = nodo << 2;

            // Las cuatro direcciones se escriben desplegadas para que el bucle interno no
            // dependa de tablas ni de saltos indirectos.
            if (x + 1 < Ciudad.ANCHO_NODOS && !bloqueada[base + RegistroBloqueos.ESTE]) {
                int vecino = nodo + 1;
                if (distanciaSalida[vecino] > distanciaVecino) {
                    distanciaSalida[vecino] = distanciaVecino;
                    predecesorSalida[vecino] = nodo;
                    cola[fin++] = vecino;
                }
            }
            if (y + 1 < Ciudad.ALTO_NODOS && !bloqueada[base + RegistroBloqueos.NORTE]) {
                int vecino = nodo + Ciudad.ANCHO_NODOS;
                if (distanciaSalida[vecino] > distanciaVecino) {
                    distanciaSalida[vecino] = distanciaVecino;
                    predecesorSalida[vecino] = nodo;
                    cola[fin++] = vecino;
                }
            }
            if (x > 0 && !bloqueada[base + RegistroBloqueos.OESTE]) {
                int vecino = nodo - 1;
                if (distanciaSalida[vecino] > distanciaVecino) {
                    distanciaSalida[vecino] = distanciaVecino;
                    predecesorSalida[vecino] = nodo;
                    cola[fin++] = vecino;
                }
            }
            if (y > 0 && !bloqueada[base + RegistroBloqueos.SUR]) {
                int vecino = nodo - Ciudad.ANCHO_NODOS;
                if (distanciaSalida[vecino] > distanciaVecino) {
                    distanciaSalida[vecino] = distanciaVecino;
                    predecesorSalida[vecino] = nodo;
                    cola[fin++] = vecino;
                }
            }
        }

        busquedas++;
        nodosVisitados += fin;
    }

    /**
     * Reconstruye la secuencia de nodos del origen al destino a partir del arreglo de
     * predecesores que dejo {@link #distanciasDesde}. El origen y el destino quedan
     * incluidos; si no hay camino se devuelve un arreglo vacio.
     *
     * <p>Solo lee el arreglo recibido y no toca los arreglos de trabajo, de modo que puede
     * llamarse mientras otro hilo usa su propio oraculo sobre los mismos predecesores.</p>
     *
     * @param predecesor  arreglo de predecesores producido desde {@code nodoOrigen}
     * @param nodoOrigen  nodo desde el que se ejecuto la busqueda
     * @param nodoDestino nodo final del camino
     */
    public int[] caminoDesdePredecesores(int[] predecesor, int nodoOrigen, int nodoDestino) {
        if (nodoOrigen == nodoDestino) {
            return new int[] {nodoOrigen};
        }
        // Primera pasada: se cuenta la longitud para asignar el arreglo del tamano exacto.
        int cantidad = 1;
        int nodo = nodoDestino;
        while (nodo != nodoOrigen) {
            nodo = predecesor[nodo];
            if (nodo < 0 || cantidad > Ciudad.TOTAL_NODOS) {
                return SIN_CAMINO;
            }
            cantidad++;
        }
        // Segunda pasada: se escribe de atras hacia adelante, que es como estan encadenados.
        int[] camino = new int[cantidad];
        nodo = nodoDestino;
        for (int i = cantidad - 1; i >= 0; i--) {
            camino[i] = nodo;
            nodo = predecesor[nodo];
        }
        return camino;
    }

    /** Numero de busquedas en anchura ejecutadas por este oraculo, para el informe de experimentacion. */
    public long busquedasEjecutadas() {
        return busquedas;
    }

    /** Numero de nodos expandidos en total, util para dimensionar el costo de la matriz. */
    public long nodosVisitados() {
        return nodosVisitados;
    }

    /** Pone a cero los contadores de instrumentacion. */
    public void reiniciarContadores() {
        busquedas = 0;
        nodosVisitados = 0;
    }
}
