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
 * unidad que llega a el debe dar la vuelta en U; ese comportamiento surge solo con retirar
 * del grafo las aristas de la poligonal, que es exactamente lo que hace esta clase.</p>
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
        }

        // Segunda pasada: cada tramo se descompone en aristas unitarias, ida y vuelta.
        this.aristas = new int[desplazamiento[cantidad]];
        int cursor = 0;
        for (int b = 0; b < cantidad; b++) {
            Bloqueo bloqueo = this.bloqueos[b];
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
                    nodo = siguiente;
                }
            }
        }

        this.instantesCambio = instantesDeCambio(minutoInicio, minutoFin);
        this.mascara = new boolean[TOTAL_ARISTAS];
        this.aristasMarcadas = new int[aristas.length];
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
     * cada iteracion de planificacion.
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
        return mascara;
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
