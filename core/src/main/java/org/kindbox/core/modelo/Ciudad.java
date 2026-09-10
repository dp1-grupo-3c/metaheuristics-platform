package org.kindbox.core.modelo;

/**
 * Reticula de la ciudad. Respuesta 4 del cuestionario: rectangulo de 70 Km (eje X)
 * por 50 Km (eje Y), nodos cada 1 Km, origen (0,0) en el extremo inferior izquierdo,
 * todas las calles de doble sentido y sin diagonales.
 *
 * <p>Un nodo se identifica por un entero {@code id = y * ANCHO_NODOS + x}, de modo que
 * todas las estructuras del planificador puedan indexarse con arreglos primitivos,
 * conforme al apartado 13 del ISA.</p>
 */
public final class Ciudad {

    /** Longitud del eje X en kilometros. */
    public static final int LARGO_KM = 70;
    /** Longitud del eje Y en kilometros. */
    public static final int ANCHO_KM = 50;
    /** Numero de nodos sobre el eje X (0..70 inclusive). */
    public static final int ANCHO_NODOS = LARGO_KM + 1;
    /** Numero de nodos sobre el eje Y (0..50 inclusive). */
    public static final int ALTO_NODOS = ANCHO_KM + 1;
    /** Cantidad total de nodos de la reticula. */
    public static final int TOTAL_NODOS = ANCHO_NODOS * ALTO_NODOS;

    private Ciudad() {
    }

    /** Empaqueta una coordenada (x,y) en el identificador entero del nodo. */
    public static int nodo(int x, int y) {
        if (!dentro(x, y)) {
            throw new IllegalArgumentException("Coordenada fuera de la ciudad: (" + x + "," + y + ")");
        }
        return y * ANCHO_NODOS + x;
    }

    /** Coordenada X del nodo. */
    public static int x(int nodo) {
        return nodo % ANCHO_NODOS;
    }

    /** Coordenada Y del nodo. */
    public static int y(int nodo) {
        return nodo / ANCHO_NODOS;
    }

    /** Indica si la coordenada pertenece a la reticula. */
    public static boolean dentro(int x, int y) {
        return x >= 0 && x < ANCHO_NODOS && y >= 0 && y < ALTO_NODOS;
    }

    /**
     * Distancia Manhattan en kilometros, que es la longitud del camino mas corto
     * sobre la reticula cuando no hay bloqueos. Sirve de cota inferior admisible
     * para la busqueda A* del oraculo de distancias.
     */
    public static int distanciaManhattan(int nodoA, int nodoB) {
        return Math.abs(x(nodoA) - x(nodoB)) + Math.abs(y(nodoA) - y(nodoB));
    }

    /** Representacion legible "(x,y)" del nodo. */
    public static String texto(int nodo) {
        return "(" + x(nodo) + "," + y(nodo) + ")";
    }
}
