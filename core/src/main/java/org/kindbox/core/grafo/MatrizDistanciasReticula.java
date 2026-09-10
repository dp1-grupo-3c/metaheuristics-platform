package org.kindbox.core.grafo;

import java.util.Arrays;
import org.kindbox.core.modelo.Ciudad;

/**
 * Matriz de tiempos y distancias del apartado 10 del ISA, calculada sobre la reticula de
 * la ciudad con los tramos bloqueados retirados del grafo.
 *
 * <p>Los puntos son los almacenes, los destinos de los pedidos pendientes y las posiciones
 * de las unidades, en el orden que fija {@code InstanciaPlanificacion}. Varios puntos
 * pueden compartir nodo, porque dos pedidos distintos pueden ir a la misma esquina y una
 * unidad puede estar parada en un almacen. La construccion aprovecha esa coincidencia y
 * ejecuta <b>una sola busqueda en anchura por nodo distinto</b>: es la optimizacion que
 * hace viable recalcular la matriz entera en cada iteracion de planificacion, dentro del
 * presupuesto de entre 2 y 18 segundos del apartado 11.</p>
 *
 * <p>Las distancias se guardan en un unico arreglo plano de {@code puntos * puntos}
 * enteros, indexado como {@code origen * puntos + destino}. Como todas las calles son de
 * doble sentido y un bloqueo retira la arista en ambos sentidos, la matriz es simetrica.</p>
 *
 * <p>Tambien se conserva el arreglo de predecesores de cada nodo distinto, de modo que
 * {@link #camino(int, int)} responda al visualizador sin repetir la busqueda. Ese es el
 * consumo de memoria dominante: {@code nodos distintos * 3 621} enteros.</p>
 *
 * <p>Terminada la construccion el objeto es inmutable. Todos sus metodos solo leen, de
 * modo que los hilos de la busqueda pueden compartir una misma matriz.</p>
 */
public final class MatrizDistanciasReticula implements MatrizDistancias {

    private final int cantidadPuntos;
    private final int[] nodoPorPunto;
    /** Distancias en kilometros, indexadas {@code origen * cantidadPuntos + destino}. */
    private final int[] distancia;
    /** Para cada punto, los demas puntos alcanzables en orden de distancia creciente. */
    private final int[][] vecinos;

    /** Indice de nodo distinto de cada punto. */
    private final int[] distintoDePunto;
    /** Nodo de cada indice distinto. */
    private final int[] nodoDistinto;
    /** Predecesores de la busqueda en anchura lanzada desde cada nodo distinto. */
    private final int[][] predecesorPorNodoDistinto;

    private final OraculoDistancias oraculo;

    /**
     * Construye la matriz con un oraculo propio.
     *
     * @param nodosPorPunto   nodo de cada punto, con repeticiones admitidas
     * @param aristaBloqueada mascara de {@link RegistroBloqueos#mascaraBloqueada(long)}
     *                        vigente en el instante de la iteracion, o {@code null} si no
     *                        hay ningun bloqueo
     */
    public static MatrizDistanciasReticula construir(int[] nodosPorPunto, boolean[] aristaBloqueada) {
        return construir(nodosPorPunto, aristaBloqueada, new OraculoDistancias());
    }

    /**
     * Construye la matriz reutilizando un oraculo entre iteraciones, que es lo que evita
     * asignar de nuevo sus arreglos de trabajo en cada replanificacion. El oraculo no debe
     * estar en uso por otro hilo mientras dure la construccion.
     */
    public static MatrizDistanciasReticula construir(int[] nodosPorPunto, boolean[] aristaBloqueada,
                                                     OraculoDistancias oraculo) {
        if (nodosPorPunto == null) {
            throw new IllegalArgumentException("La lista de nodos por punto no puede ser nula");
        }
        if (oraculo == null) {
            throw new IllegalArgumentException("La matriz necesita un oraculo de distancias");
        }
        return new MatrizDistanciasReticula(nodosPorPunto, aristaBloqueada, oraculo);
    }

    private MatrizDistanciasReticula(int[] nodosPorPunto, boolean[] aristaBloqueada,
                                     OraculoDistancias oraculo) {
        this.oraculo = oraculo;
        this.cantidadPuntos = nodosPorPunto.length;
        this.nodoPorPunto = nodosPorPunto.clone();
        this.distancia = new int[cantidadPuntos * cantidadPuntos];
        this.distintoDePunto = new int[cantidadPuntos];

        // Deduplicacion de nodos con una tabla directa indexada por nodo, que resuelve en
        // tiempo constante y sin envoltorios ni tablas de dispersion.
        int[] distintoDeNodo = new int[Ciudad.TOTAL_NODOS];
        Arrays.fill(distintoDeNodo, -1);
        int[] nodosDistintos = new int[cantidadPuntos];
        int cantidadDistintos = 0;
        for (int p = 0; p < cantidadPuntos; p++) {
            int nodo = nodoPorPunto[p];
            if (nodo < 0 || nodo >= Ciudad.TOTAL_NODOS) {
                throw new IllegalArgumentException("Nodo fuera de la ciudad en el punto " + p + ": " + nodo);
            }
            int indice = distintoDeNodo[nodo];
            if (indice < 0) {
                indice = cantidadDistintos;
                distintoDeNodo[nodo] = indice;
                nodosDistintos[cantidadDistintos++] = nodo;
            }
            distintoDePunto[p] = indice;
        }
        this.nodoDistinto = Arrays.copyOf(nodosDistintos, cantidadDistintos);
        this.predecesorPorNodoDistinto = new int[cantidadDistintos][];

        // Una busqueda en anchura por nodo distinto. La fila obtenida se copia a todos los
        // puntos que comparten ese nodo, que es la ganancia de la deduplicacion.
        int[] distanciaPorNodo = new int[Ciudad.TOTAL_NODOS];
        int[] fila = new int[cantidadPuntos];
        int maximaDistancia = 0;
        for (int k = 0; k < cantidadDistintos; k++) {
            int[] predecesor = new int[Ciudad.TOTAL_NODOS];
            oraculo.distanciasDesde(nodoDistinto[k], aristaBloqueada, distanciaPorNodo, predecesor);
            predecesorPorNodoDistinto[k] = predecesor;
            for (int j = 0; j < cantidadPuntos; j++) {
                int km = distanciaPorNodo[nodoPorPunto[j]];
                fila[j] = km;
                if (km < INALCANZABLE && km > maximaDistancia) {
                    maximaDistancia = km;
                }
            }
            for (int p = 0; p < cantidadPuntos; p++) {
                if (distintoDePunto[p] == k) {
                    System.arraycopy(fila, 0, distancia, p * cantidadPuntos, cantidadPuntos);
                }
            }
        }

        this.vecinos = calcularVecinos(cantidadPuntos, distancia, maximaDistancia);
    }

    /**
     * Vecinos ordenados por distancia creciente, con un ordenamiento por conteo sobre la
     * distancia. Las distancias son enteros pequenos acotados por el diametro de la
     * ciudad, de modo que el conteo es lineal y ademas deja un orden estable por indice de
     * punto, con lo que dos corridas con la misma semilla recorren el mismo vecindario.
     */
    private static int[][] calcularVecinos(int cantidadPuntos, int[] distancia, int maximaDistancia) {
        int[][] vecinos = new int[cantidadPuntos][];
        int[] conteo = new int[maximaDistancia + 2];
        for (int a = 0; a < cantidadPuntos; a++) {
            Arrays.fill(conteo, 0);
            int base = a * cantidadPuntos;
            int alcanzables = 0;
            for (int b = 0; b < cantidadPuntos; b++) {
                if (b == a) {
                    continue;
                }
                int km = distancia[base + b];
                if (km < INALCANZABLE) {
                    // El desplazamiento en uno convierte el conteo en el inicio de cada grupo.
                    conteo[km + 1]++;
                    alcanzables++;
                }
            }
            for (int d = 1; d < conteo.length; d++) {
                conteo[d] += conteo[d - 1];
            }
            int[] orden = new int[alcanzables];
            for (int b = 0; b < cantidadPuntos; b++) {
                if (b == a) {
                    continue;
                }
                int km = distancia[base + b];
                if (km < INALCANZABLE) {
                    orden[conteo[km]++] = b;
                }
            }
            vecinos[a] = orden;
        }
        return vecinos;
    }

    @Override
    public int cantidadPuntos() {
        return cantidadPuntos;
    }

    @Override
    public int nodo(int punto) {
        return nodoPorPunto[punto];
    }

    @Override
    public int km(int puntoOrigen, int puntoDestino) {
        return distancia[puntoOrigen * cantidadPuntos + puntoDestino];
    }

    /**
     * {@inheritDoc}
     *
     * <p>Excluye el propio punto y los puntos que los bloqueos dejaron inalcanzables. Se
     * devuelve siempre el mismo arreglo, que <b>no debe modificarse</b>: lo comparten
     * todas las evaluaciones de movimientos de la corrida.</p>
     */
    @Override
    public int[] vecinosCercanos(int punto) {
        return vecinos[punto];
    }

    @Override
    public int[] camino(int puntoOrigen, int puntoDestino) {
        int[] predecesor = predecesorPorNodoDistinto[distintoDePunto[puntoOrigen]];
        return oraculo.caminoDesdePredecesores(predecesor, nodoPorPunto[puntoOrigen], nodoPorPunto[puntoDestino]);
    }

    /**
     * Numero de busquedas en anchura que costo construir la matriz, es decir la cantidad
     * de nodos distintos. Alimenta el informe de experimentacion del apartado 12: junto
     * con {@link #cantidadPuntos()} mide cuanto ahorro aporto la deduplicacion.
     */
    public int busquedasEnAnchura() {
        return nodoDistinto.length;
    }

    /** Nodo que corresponde al indice de nodo distinto dado. */
    public int nodoDistinto(int indice) {
        return nodoDistinto[indice];
    }
}
