package org.kindbox.core.grafo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;

/**
 * Registro de todos los tramos bloqueados del escenario y de su vigencia en el tiempo.
 *
 * <p>Materializa la restriccion dura 6 del apartado 2.6 del ISA: los tramos bloqueados no
 * son transitables durante su ventana de vigencia. La respuesta 7 del cuestionario precisa
 * que un nodo bloqueado no se puede atravesar ni se puede girar en el, de modo que una
 * unidad que llega a el debe dar la vuelta en U.</p>
 *
 * <p>Retirar solo las aristas de la poligonal no basta: impide recorrer el bloqueo a lo
 * largo, pero deja cruzarlo de costado por cualquiera de sus nodos, entrando por una calle
 * perpendicular y saliendo por la opuesta. Por eso todos los nodos de la poligonal, extremos
 * incluidos, se tratan como nodos bloqueados: de cada uno se retiran todas las calles salvo
 * una, su <b>calle de acceso</b>. El nodo queda como un callejon sin salida al que se llega
 * por esa calle y del que solo se sale por la misma, que es la vuelta en U. Un pedido cuyo
 * destino cae en un nodo bloqueado sigue siendo atendible, y ninguna ruta lo atraviesa. La
 * regla vive en la mascara de aristas, de modo que la busqueda en anchura, la matriz y el
 * camino que sigue cada unidad la respetan sin cambio alguno.</p>
 *
 * <p>La calle de acceso se elige de forma determinista: primero una que lleve a un nodo no
 * bloqueado; en un extremo, la que prolonga la poligonal hacia afuera; y luego el orden
 * norte, este, sur, oeste. Es una aproximacion: la vuelta en U real podria hacerse por
 * cualquier lado, segun por donde llegue la unidad, pero la matriz de distancias mide cada
 * tramo por separado y no recuerda por que calle se entro. Fijar un lado garantiza que la
 * ruta planificada sea siempre valida, a costa de algun desvio cuando el lado elegido no es
 * el mas cercano.</p>
 *
 * <p>Una arista de la reticula se codifica como {@code arista = nodo * 4 + direccion}, con
 * {@link #ESTE}, {@link #NORTE}, {@link #OESTE} y {@link #SUR} como direcciones. Como todas
 * las calles son de doble sentido (requisito no funcional (c)), una arista y su inversa son
 * la misma calle: bloquear una bloquea las dos, y el registro siempre marca ambas.</p>
 *
 * <p>Un tramo de la poligonal puede unir dos esquinas separadas por varios kilometros
 * siempre que compartan fila o columna; el registro lo descompone en las aristas unitarias
 * intermedias. Un tramo diagonal se rechaza, porque la ciudad no tiene diagonales.</p>
 *
 * <p>El motor de simulacion pide la mascara de aristas bloqueadas en cada iteracion de
 * planificacion, de modo que el conjunto vigente se cachea y solo se recalcula cuando el
 * instante consultado sale del intervalo entre dos cambios consecutivos. Por esa cache la
 * clase <b>no</b> es segura para uso concurrente: cada hilo de simulacion usa la suya.</p>
 */
public final class RegistroBloqueos {

    /** Direccion hacia el este, es decir hacia {@code x + 1}. */
    public static final int ESTE = 0;
    /** Direccion hacia el norte, es decir hacia {@code y + 1}. */
    public static final int NORTE = 1;
    /** Direccion hacia el oeste, es decir hacia {@code x - 1}. */
    public static final int OESTE = 2;
    /** Direccion hacia el sur, es decir hacia {@code y - 1}. */
    public static final int SUR = 3;
    /** Numero de direcciones que salen de un nodo de la reticula. */
    public static final int DIRECCIONES = 4;
    /** Dimension de la mascara de aristas: cuatro aristas por nodo, existan o no. */
    public static final int TOTAL_ARISTAS = Ciudad.TOTAL_NODOS * DIRECCIONES;

    private static final RegistroBloqueos VACIO = new RegistroBloqueos(List.of());

    private final Bloqueo[] bloqueos;
    private final long[] minutoInicio;
    private final long[] minutoFin;

    /** Aristas de todos los bloqueos concatenadas, ida y vuelta de cada tramo unitario. */
    private final int[] aristas;
    /** Rango {@code [desplazamiento[b], desplazamiento[b+1])} de las aristas del bloqueo {@code b}. */
    private final int[] desplazamiento;
    /** Union ordenada y sin repeticiones de los instantes de activacion y desactivacion. */
    private final long[] instantesCambio;

    private final boolean[] mascara;
    /** Aristas marcadas en la mascara vigente, para limpiarla sin recorrerla entera. */
    private final int[] aristasMarcadas;

    /** Nodos de todos los bloqueos concatenados, extremos incluidos, a un kilometro uno de otro. */
    private final int[] nodos;
    /** Rango {@code [desplazamientoNodos[b], desplazamientoNodos[b+1])} de los nodos del bloqueo {@code b}. */
    private final int[] desplazamientoNodos;
    /** Marca de cada nodo bloqueado en el calculo vigente de la mascara, igual a {@link #sello}. */
    private final int[] selloNodo = new int[Ciudad.TOTAL_NODOS];
    private int sello;
    /** Nodos bloqueados vigentes y su calle de acceso, arreglos de trabajo del calculo de la mascara. */
    private final int[] nodosVigentes;
    private final int[] accesoVigente;
    private int cantidadMarcadas;

    // Intervalo [desde, hasta) en que la mascara cacheada es valida. El intervalo inicial
    // es vacio a proposito, de modo que la primera consulta siempre recalcule.
    private long vigenciaDesde = Long.MAX_VALUE;
    private long vigenciaHasta = Long.MIN_VALUE;

    /**
     * Construye el registro a partir de los bloqueos leidos del archivo mensual
     * {@code aaaammbloqueadas}.
     *
     * @param bloqueos bloqueos del escenario, en cualquier orden
     */
    public RegistroBloqueos(List<Bloqueo> bloqueos) {
        if (bloqueos == null) {
            throw new IllegalArgumentException("La lista de bloqueos no puede ser nula");
        }
        int cantidad = bloqueos.size();
        this.bloqueos = bloqueos.toArray(new Bloqueo[0]);
        this.minutoInicio = new long[cantidad];
        this.minutoFin = new long[cantidad];
        this.desplazamiento = new int[cantidad + 1];
        this.desplazamientoNodos = new int[cantidad + 1];

        // Primera pasada: se cuentan las aristas para dimensionar el arreglo plano de una vez.
        for (int b = 0; b < cantidad; b++) {
            Bloqueo bloqueo = this.bloqueos[b];
            minutoInicio[b] = bloqueo.minutoInicio();
            minutoFin[b] = bloqueo.minutoFin();
            int aristasDelBloqueo = 0;
            for (int t = 0; t < bloqueo.cantidadTramos(); t++) {
                int nodoA = bloqueo.nodoOrigenTramo(t);
                int nodoB = bloqueo.nodoDestinoTramo(t);
                direccionDelTramo(nodoA, nodoB);
                aristasDelBloqueo += 2 * Ciudad.distanciaManhattan(nodoA, nodoB);
            }
            desplazamiento[b + 1] = desplazamiento[b] + aristasDelBloqueo;
            // Una poligonal de n aristas unitarias tiene n + 1 nodos.
            desplazamientoNodos[b + 1] = desplazamientoNodos[b] + aristasDelBloqueo / 2 + 1;
        }

        // Segunda pasada: cada tramo se descompone en aristas unitarias, ida y vuelta.
        this.aristas = new int[desplazamiento[cantidad]];
        this.nodos = new int[desplazamientoNodos[cantidad]];
        int cursor = 0;
        int cursorNodos = 0;
        for (int b = 0; b < cantidad; b++) {
            Bloqueo bloqueo = this.bloqueos[b];
            nodos[cursorNodos++] = bloqueo.nodoOrigenTramo(0);
            for (int t = 0; t < bloqueo.cantidadTramos(); t++) {
                int nodoA = bloqueo.nodoOrigenTramo(t);
                int nodoB = bloqueo.nodoDestinoTramo(t);
                int direccion = direccionDelTramo(nodoA, nodoB);
                int pasos = Ciudad.distanciaManhattan(nodoA, nodoB);
                int nodo = nodoA;
                for (int k = 0; k < pasos; k++) {
                    int siguiente = nodoVecino(nodo, direccion);
                    aristas[cursor++] = arista(nodo, direccion);
                    aristas[cursor++] = arista(siguiente, direccionOpuesta(direccion));
                    nodos[cursorNodos++] = siguiente;
                    nodo = siguiente;
                }
            }
        }

        this.instantesCambio = instantesDeCambio(minutoInicio, minutoFin);
        this.mascara = new boolean[TOTAL_ARISTAS];
        // Ademas de la poligonal se marcan las calles laterales de cada nodo bloqueado.
        this.aristasMarcadas = new int[TOTAL_ARISTAS];
        this.nodosVigentes = new int[nodos.length];
        this.accesoVigente = new int[nodos.length];
    }

    /**
     * Registro sin ningun bloqueo, para los escenarios que no cargan el archivo mensual.
     * Se comparte una sola instancia porque su mascara nunca llega a escribirse.
     */
    public static RegistroBloqueos vacio() {
        return VACIO;
    }

    // ------------------------------------------------- codificacion de aristas

    /** Codifica la arista que sale del nodo en la direccion dada. */
    public static int arista(int nodo, int direccion) {
        return (nodo << 2) + direccion;
    }

    /** Nodo del que sale la arista. */
    public static int nodoDeArista(int arista) {
        return arista >>> 2;
    }

    /** Direccion de la arista, entre {@link #ESTE} y {@link #SUR}. */
    public static int direccionDeArista(int arista) {
        return arista & 3;
    }

    /** Direccion contraria a la dada. */
    public static int direccionOpuesta(int direccion) {
        return (direccion + 2) & 3;
    }

    /** Nodo contiguo en la direccion dada, o {@code -1} si cae fuera de la ciudad. */
    public static int nodoVecino(int nodo, int direccion) {
        int x = Ciudad.x(nodo);
        int y = Ciudad.y(nodo);
        return switch (direccion) {
            case ESTE -> x + 1 < Ciudad.ANCHO_NODOS ? nodo + 1 : -1;
            case NORTE -> y + 1 < Ciudad.ALTO_NODOS ? nodo + Ciudad.ANCHO_NODOS : -1;
            case OESTE -> x > 0 ? nodo - 1 : -1;
            case SUR -> y > 0 ? nodo - Ciudad.ANCHO_NODOS : -1;
            default -> throw new IllegalArgumentException("Direccion invalida: " + direccion);
        };
    }

    /** Direccion que lleva de un nodo al otro, o {@code -1} si no son contiguos. */
    public static int direccionEntre(int nodoA, int nodoB) {
        int dx = Ciudad.x(nodoB) - Ciudad.x(nodoA);
        int dy = Ciudad.y(nodoB) - Ciudad.y(nodoA);
        if (dy == 0 && dx == 1) {
            return ESTE;
        }
        if (dx == 0 && dy == 1) {
            return NORTE;
        }
        if (dy == 0 && dx == -1) {
            return OESTE;
        }
        if (dx == 0 && dy == -1) {
            return SUR;
        }
        return -1;
    }

    /** Arista que une dos nodos contiguos, o {@code -1} si no lo son. */
    public static int aristaEntre(int nodoA, int nodoB) {
        int direccion = direccionEntre(nodoA, nodoB);
        return direccion < 0 ? -1 : arista(nodoA, direccion);
    }

    /**
     * Arista que recorre la misma calle en sentido contrario, o {@code -1} si la arista
     * sale de la ciudad. Ambas se bloquean y se desbloquean juntas.
     */
    public static int aristaInversa(int arista) {
        int direccion = direccionDeArista(arista);
        int vecino = nodoVecino(nodoDeArista(arista), direccion);
        return vecino < 0 ? -1 : arista(vecino, direccionOpuesta(direccion));
    }

    // ------------------------------------------------------------- consultas

    /**
     * Mascara de las aristas bloqueadas vigentes en el instante dado, de dimension
     * {@link #TOTAL_ARISTAS}. La consume la construccion de la matriz de distancias en
     * cada iteracion de planificacion. Incluye las aristas de las poligonales y, de cada
     * nodo bloqueado, todas sus calles salvo la de acceso (ver la documentacion de la clase).
     *
     * <p>Se devuelve siempre el mismo arreglo, recalculado solo cuando el instante sale
     * del intervalo en que el conjunto vigente no cambia. <b>No debe modificarse</b>: una
     * escritura del llamante corrompe todas las consultas posteriores.</p>
     */
    public boolean[] mascaraBloqueada(long minuto) {
        if (minuto >= vigenciaDesde && minuto < vigenciaHasta) {
            return mascara;
        }
        int corte = indiceProximoCambio(minuto);
        vigenciaDesde = corte > 0 ? instantesCambio[corte - 1] : Long.MIN_VALUE;
        vigenciaHasta = corte < instantesCambio.length ? instantesCambio[corte] : Long.MAX_VALUE;

        // Se limpian solo las aristas que quedaron marcadas, y no la mascara entera.
        for (int i = 0; i < cantidadMarcadas; i++) {
            mascara[aristasMarcadas[i]] = false;
        }
        cantidadMarcadas = 0;
        for (int b = 0; b < bloqueos.length; b++) {
            if (minuto >= minutoInicio[b] && minuto < minutoFin[b]) {
                int fin = desplazamiento[b + 1];
                for (int k = desplazamiento[b]; k < fin; k++) {
                    int arista = aristas[k];
                    // La comprobacion evita anotar dos veces una arista compartida por
                    // dos bloqueos superpuestos, que la dejaria marcada al limpiarla.
                    if (!mascara[arista]) {
                        mascara[arista] = true;
                        aristasMarcadas[cantidadMarcadas++] = arista;
                    }
                }
            }
        }
        bloquearNodos(minuto);
        return mascara;
    }

    /**
     * Convierte cada nodo de las poligonales vigentes en un callejon sin salida: le deja solo
     * la calle de acceso. Las calles de acceso se eligen todas antes de marcar ninguna, para
     * que la eleccion de un nodo no dependa del orden en que se recorren los bloqueos.
     */
    private void bloquearNodos(long minuto) {
        sello++;
        int vigentes = 0;
        for (int b = 0; b < bloqueos.length; b++) {
            if (minuto < minutoInicio[b] || minuto >= minutoFin[b]) {
                continue;
            }
            for (int k = desplazamientoNodos[b]; k < desplazamientoNodos[b + 1]; k++) {
                int nodo = nodos[k];
                if (selloNodo[nodo] != sello) {
                    selloNodo[nodo] = sello;
                    nodosVigentes[vigentes++] = nodo;
                }
            }
        }
        for (int i = 0; i < vigentes; i++) {
            accesoVigente[i] = calleDeAcceso(nodosVigentes[i]);
        }
        for (int i = 0; i < vigentes; i++) {
            int nodo = nodosVigentes[i];
            for (int direccion = 0; direccion < DIRECCIONES; direccion++) {
                int vecino = nodoVecino(nodo, direccion);
                if (vecino < 0 || direccion == accesoVigente[i]) {
                    continue;
                }
                marcar(arista(nodo, direccion));
                marcar(arista(vecino, direccionOpuesta(direccion)));
            }
        }
    }

    /**
     * Calle de acceso de un nodo bloqueado, o {@code -1} si todas sus calles son de la
     * poligonal. Se lee con la mascara que solo tiene las poligonales marcadas.
     */
    private int calleDeAcceso(int nodo) {
        // En un extremo hay una sola arista de poligonal; la prolongacion es la opuesta.
        int aristasDePoligonal = 0;
        int direccionPoligonal = -1;
        for (int direccion = 0; direccion < DIRECCIONES; direccion++) {
            if (nodoVecino(nodo, direccion) >= 0 && mascara[arista(nodo, direccion)]) {
                aristasDePoligonal++;
                direccionPoligonal = direccion;
            }
        }
        int prolongacion = aristasDePoligonal == 1 ? direccionOpuesta(direccionPoligonal) : -1;

        int mejor = -1;
        int mejorPuntaje = Integer.MIN_VALUE;
        for (int direccion : ORDEN_ACCESO) {
            int vecino = nodoVecino(nodo, direccion);
            if (vecino < 0 || mascara[arista(nodo, direccion)]) {
                continue;
            }
            int puntaje = (selloNodo[vecino] != sello ? 2 : 0) + (direccion == prolongacion ? 1 : 0);
            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = direccion;
            }
        }
        return mejor;
    }

    /** Orden de desempate de la calle de acceso. */
    private static final int[] ORDEN_ACCESO = {NORTE, ESTE, SUR, OESTE};

    private void marcar(int arista) {
        if (!mascara[arista]) {
            mascara[arista] = true;
            aristasMarcadas[cantidadMarcadas++] = arista;
        }
    }

    /**
     * Primer instante estrictamente posterior a {@code minuto} en que algun bloqueo se
     * activa o se desactiva, o {@link Long#MAX_VALUE} si ya no queda ninguno. Lo consume
     * la cola de eventos del simulador para saber cuando replanificar.
     */
    public long proximoCambio(long minuto) {
        int corte = indiceProximoCambio(minuto);
        return corte < instantesCambio.length ? instantesCambio[corte] : Long.MAX_VALUE;
    }

    /** Bloqueos vigentes en el instante dado, en una lista nueva para el visualizador. */
    public List<Bloqueo> vigentes(long minuto) {
        List<Bloqueo> lista = new ArrayList<>();
        for (int b = 0; b < bloqueos.length; b++) {
            if (minuto >= minutoInicio[b] && minuto < minutoFin[b]) {
                lista.add(bloqueos[b]);
            }
        }
        return lista;
    }

    /**
     * Indica si la calle que une dos nodos contiguos esta bloqueada en el instante dado.
     * Consulta puntual para el verificador y el visualizador; los recorridos masivos usan
     * {@link #mascaraBloqueada(long)}.
     *
     * @throws IllegalArgumentException si los nodos no son contiguos en la reticula
     */
    public boolean bloqueada(int nodoA, int nodoB, long minuto) {
        int arista = aristaEntre(nodoA, nodoB);
        if (arista < 0) {
            throw new IllegalArgumentException("Los nodos no son contiguos: "
                    + Ciudad.texto(nodoA) + " y " + Ciudad.texto(nodoB));
        }
        return mascaraBloqueada(minuto)[arista];
    }

    /** Numero de bloqueos registrados. */
    public int cantidadBloqueos() {
        return bloqueos.length;
    }

    /** Indica si el registro no contiene ningun bloqueo. */
    public boolean sinBloqueos() {
        return bloqueos.length == 0;
    }

    /** Todos los bloqueos del escenario, en una lista nueva e independiente. */
    public List<Bloqueo> todos() {
        return new ArrayList<>(Arrays.asList(bloqueos));
    }

    // ------------------------------------------------------------- internos

    /** Posicion del primer instante de cambio estrictamente mayor que {@code minuto}. */
    private int indiceProximoCambio(long minuto) {
        int bajo = 0;
        int alto = instantesCambio.length;
        while (bajo < alto) {
            int medio = (bajo + alto) >>> 1;
            if (instantesCambio[medio] <= minuto) {
                bajo = medio + 1;
            } else {
                alto = medio;
            }
        }
        return bajo;
    }

    /** Direccion de un tramo de la poligonal, que debe compartir fila o columna. */
    private static int direccionDelTramo(int nodoA, int nodoB) {
        int dx = Ciudad.x(nodoB) - Ciudad.x(nodoA);
        int dy = Ciudad.y(nodoB) - Ciudad.y(nodoA);
        if (dx != 0 && dy != 0) {
            throw new IllegalArgumentException("Tramo diagonal, la ciudad no tiene diagonales: "
                    + Ciudad.texto(nodoA) + " a " + Ciudad.texto(nodoB));
        }
        if (dx == 0 && dy == 0) {
            throw new IllegalArgumentException("Tramo de longitud cero en " + Ciudad.texto(nodoA));
        }
        if (dy == 0) {
            return dx > 0 ? ESTE : OESTE;
        }
        return dy > 0 ? NORTE : SUR;
    }

    /** Union ordenada y sin repeticiones de los extremos de todas las ventanas. */
    private static long[] instantesDeCambio(long[] inicios, long[] fines) {
        int cantidad = inicios.length;
        long[] todos = new long[cantidad * 2];
        System.arraycopy(inicios, 0, todos, 0, cantidad);
        System.arraycopy(fines, 0, todos, cantidad, cantidad);
        Arrays.sort(todos);
        int distintos = 0;
        for (int i = 0; i < todos.length; i++) {
            if (i == 0 || todos[i] != todos[i - 1]) {
                todos[distintos++] = todos[i];
            }
        }
        return Arrays.copyOf(todos, distintos);
    }
}
