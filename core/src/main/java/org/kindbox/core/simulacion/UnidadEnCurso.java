package org.kindbox.core.simulacion;

import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.Ruta;

/**
 * Estado de ejecucion de una unidad de transporte dentro del motor de simulacion.
 *
 * <p>{@link UnidadTransporte} guarda lo que el planificador necesita saber de la unidad: su
 * nodo, su carga y el instante desde el que admite ruta. Esta clase guarda lo que solo el
 * motor necesita: el itinerario comprometido, el camino nodo a nodo que lo materializa y la
 * posicion dentro de ese camino en cualquier instante.</p>
 *
 * <h2>El camino y su relacion con el tiempo</h2>
 * <p>El camino es la secuencia de nodos de la reticula que la unidad recorre de principio a
 * fin del itinerario. Como la ciudad tiene un nodo cada kilometro y no admite diagonales,
 * <b>dos nodos consecutivos del camino distan exactamente un kilometro</b>. Esa propiedad es
 * la que permite cobrar los kilometros y el costo de operacion contando indices, sin
 * arrastrar sumas paralelas que pudieran desviarse de lo realmente recorrido cuando una
 * replanificacion interrumpe un tramo a medias.</p>
 *
 * <p>El itinerario se describe con tres arreglos paralelos: el desplazamiento en el camino
 * de cada parada, su instante de llegada y su instante de salida. La posicion de la unidad
 * dentro de un tramo se interpola de forma lineal en el tiempo entre la salida de la parada
 * anterior y la llegada a la siguiente. Como la velocidad es constante dentro de un tramo,
 * interpolar en tiempo equivale a interpolar en distancia.</p>
 *
 * <p>El camino de un itinerario recien comprometido arranca en el nodo <b>fisico</b> de la
 * unidad, que puede no coincidir con el nodo desde el que se planifico: una unidad
 * sorprendida a mitad de calle por la replanificacion no puede darse la vuelta, de modo que
 * se planifica desde el proximo nodo que alcanzara y el kilometro que le falta para llegar a
 * el se incorpora como cabecera del camino nuevo. Asi ese kilometro se dibuja y se cobra
 * igual que cualquier otro.</p>
 *
 * <p>La clase no es segura para uso concurrente. Solo el hilo de la simulacion la modifica;
 * las lecturas del visualizador se hacen bajo el candado del motor.</p>
 */
public final class UnidadEnCurso {

    /** Valor de {@link #indiceParada()} cuando la unidad no tiene itinerario en curso. */
    public static final int SIN_PARADA = -2;
    /**
     * Valor de {@link #indiceParada()} mientras la unidad termina el servicio heredado del
     * itinerario anterior. Una replanificacion no interrumpe la hora de acondicionamiento
     * de un producto ya descargado ni la pausa de alimentacion del conductor: el itinerario
     * nuevo queda comprometido y arranca cuando ese servicio cierra.
     */
    public static final int SERVICIO_HEREDADO = -1;

    private static final int[] SIN_NODOS = new int[0];
    private static final long[] SIN_MINUTOS = new long[0];

    private final int indice;
    private final UnidadTransporte unidad;

    private Ruta ruta;
    private int[] camino = SIN_NODOS;
    private int[] desplazamientoParada = SIN_NODOS;
    private long[] minutoLlegadaParada = SIN_MINUTOS;
    private long[] minutoSalidaParada = SIN_MINUTOS;

    private int indiceParada = SIN_PARADA;
    private boolean sirviendo;
    private long minutoSalidaTramo;
    private long minutoFinServicioHeredado = Long.MIN_VALUE;
    private int indiceCobrado;
    private long secuenciaMovimiento = -1L;

    private boolean misionTrasvase;
    private int unidadAsistida = -1;
    private TipoAveria averia;
    /** Inicio del turno en que la unidad cumplio su pausa de alimentacion, o MIN_VALUE. */
    private long turnoPausaAcreditada = Long.MIN_VALUE;
    /** Instante desde el que la unidad esta detenida sin itinerario, o MIN_VALUE si trabaja. */
    private long inactivaDesde = 0L;

    /**
     * @param indice posicion de la unidad en la flota, que es la referencia que viaja en los
     *               eventos del motor
     * @param unidad estado que comparte con el planificador
     */
    public UnidadEnCurso(int indice, UnidadTransporte unidad) {
        this.indice = indice;
        this.unidad = unidad;
        this.camino = new int[] {unidad.nodo()};
    }

    /** Posicion de la unidad en la flota. */
    public int indice() {
        return indice;
    }

    /** Estado que comparte con el planificador. */
    public UnidadTransporte unidad() {
        return unidad;
    }

    /** Codigo TTNN de la unidad. */
    public String codigo() {
        return unidad.codigo();
    }

    // ------------------------------------------------------------- itinerario

    /**
     * Compromete un itinerario nuevo.
     *
     * @param ruta                 plan del que procede, o {@code null} si es una mision de
     *                             trasvase o una parada tecnica sin pedidos
     * @param camino               nodos consecutivos, a un kilometro uno de otro, que arranca
     *                             en el nodo fisico de la unidad
     * @param desplazamientoParada indice en {@code camino} de cada parada del itinerario
     * @param minutoLlegadaParada  instante de llegada a cada parada
     * @param minutoSalidaParada   instante de salida de cada parada
     * @param minutoSalidaTramo    instante en que arranca el desplazamiento hacia la primera
     *                             parada
     */
    public void asignarItinerario(Ruta ruta, int[] camino, int[] desplazamientoParada,
                                  long[] minutoLlegadaParada, long[] minutoSalidaParada,
                                  long minutoSalidaTramo) {
        this.ruta = ruta;
        this.camino = camino.length == 0 ? new int[] {unidad.nodo()} : camino;
        this.desplazamientoParada = desplazamientoParada;
        this.minutoLlegadaParada = minutoLlegadaParada;
        this.minutoSalidaParada = minutoSalidaParada;
        this.minutoSalidaTramo = minutoSalidaTramo;
        this.indiceParada = desplazamientoParada.length == 0 ? SIN_PARADA : 0;
        this.sirviendo = false;
        this.minutoFinServicioHeredado = Long.MIN_VALUE;
        this.indiceCobrado = 0;
    }

    /** Deja a la unidad sin itinerario, parada en el nodo indicado. */
    public void limpiarItinerario(int nodo) {
        this.ruta = null;
        this.camino = new int[] {nodo};
        this.desplazamientoParada = SIN_NODOS;
        this.minutoLlegadaParada = SIN_MINUTOS;
        this.minutoSalidaParada = SIN_MINUTOS;
        this.indiceParada = SIN_PARADA;
        this.sirviendo = false;
        this.minutoSalidaTramo = 0L;
        this.minutoFinServicioHeredado = Long.MIN_VALUE;
        this.indiceCobrado = 0;
        this.secuenciaMovimiento = -1L;
        this.misionTrasvase = false;
        this.unidadAsistida = -1;
    }

    /** Plan del que procede el itinerario vigente, o {@code null} si no lo hay. */
    public Ruta ruta() {
        return ruta;
    }

    /** Paradas del itinerario vigente. */
    public int cantidadParadas() {
        return desplazamientoParada.length;
    }

    /** Indice de la parada hacia la que se dirige, o en la que sirve. */
    public int indiceParada() {
        return indiceParada;
    }

    /** Indica si la unidad esta detenida sirviendo una parada. */
    public boolean sirviendo() {
        return sirviendo;
    }

    /** Indica si la unidad tiene un itinerario con paradas por delante. */
    public boolean conItinerario() {
        return indiceParada >= 0 || indiceParada == SERVICIO_HEREDADO;
    }

    /** Desplazamiento en el camino de la parada indicada. */
    public int desplazamientoParada(int k) {
        return desplazamientoParada[k];
    }

    /** Instante de llegada previsto a la parada indicada. */
    public long minutoLlegadaParada(int k) {
        return minutoLlegadaParada[k];
    }

    /** Instante de salida previsto de la parada indicada. */
    public long minutoSalidaParada(int k) {
        return minutoSalidaParada[k];
    }

    /**
     * Instante de salida del servicio que la unidad esta atendiendo, sea una parada del
     * itinerario vigente o el servicio heredado del anterior. Devuelve
     * {@link Long#MIN_VALUE} si la unidad no esta sirviendo.
     */
    public long minutoSalidaParadaEnCurso() {
        if (indiceParada == SERVICIO_HEREDADO) {
            return minutoFinServicioHeredado;
        }
        return sirviendo && indiceParada >= 0 ? minutoSalidaParada[indiceParada] : Long.MIN_VALUE;
    }

    /** Marca que la unidad llego a la parada indicada y arranca su servicio. */
    public void llegarA(int k) {
        this.indiceParada = k;
        this.sirviendo = true;
    }

    /** Marca que la unidad arranca el desplazamiento hacia la parada indicada. */
    public void partirHacia(int k, long minuto) {
        this.indiceParada = k;
        this.sirviendo = false;
        this.minutoSalidaTramo = minuto;
    }

    /**
     * Marca que la unidad esta terminando el servicio heredado del itinerario anterior.
     *
     * @param minutoFin instante en que cierra ese servicio, que es el arranque efectivo del
     *                  itinerario recien comprometido
     */
    public void marcarServicioHeredado(long minutoFin) {
        this.indiceParada = SERVICIO_HEREDADO;
        this.sirviendo = true;
        this.minutoFinServicioHeredado = minutoFin;
    }

    /** Marca que la unidad agoto su itinerario y queda parada donde esta. */
    public void terminarItinerario() {
        this.indiceParada = SIN_PARADA;
        this.sirviendo = false;
    }

    // -------------------------------------------------------------- posicion

    /**
     * Indice en el camino del ultimo nodo por el que la unidad ya paso.
     *
     * <p>Dentro de un tramo la posicion se interpola de forma lineal en el tiempo entre la
     * salida de la parada anterior y la llegada a la siguiente, y se trunca al nodo, porque
     * una unidad a mitad de calle no puede maniobrar y el nodo anterior es el ultimo punto
     * en que efectivamente estuvo.</p>
     */
    public int indiceNodoActual(long minuto) {
        if (camino.length <= 1) {
            return 0;
        }
        if (indiceParada == SIN_PARADA) {
            return camino.length - 1;
        }
        if (indiceParada == SERVICIO_HEREDADO) {
            return 0;
        }
        int fin = desplazamientoParada[indiceParada];
        if (sirviendo) {
            return fin;
        }
        int inicio = indiceParada == 0 ? 0 : desplazamientoParada[indiceParada - 1];
        if (fin <= inicio) {
            return fin;
        }
        long llegada = minutoLlegadaParada[indiceParada];
        if (minuto >= llegada) {
            return fin;
        }
        if (minuto <= minutoSalidaTramo || llegada <= minutoSalidaTramo) {
            return inicio;
        }
        double fraccion = (double) (minuto - minutoSalidaTramo) / (llegada - minutoSalidaTramo);
        int avance = (int) Math.floor(fraccion * (fin - inicio));
        return inicio + Math.max(0, Math.min(avance, fin - inicio));
    }

    /**
     * Instante en que la unidad alcanza el nodo del camino indicado, con la misma
     * interpolacion lineal que usa {@link #indiceNodoActual(long)}.
     */
    public long minutoLlegadaANodo(int indiceEnCamino) {
        if (indiceParada < 0) {
            return minutoSalidaTramo;
        }
        int fin = desplazamientoParada[indiceParada];
        int inicio = indiceParada == 0 ? 0 : desplazamientoParada[indiceParada - 1];
        long llegada = minutoLlegadaParada[indiceParada];
        if (fin <= inicio || indiceEnCamino <= inicio) {
            return minutoSalidaTramo;
        }
        if (indiceEnCamino >= fin) {
            return llegada;
        }
        double fraccion = (double) (indiceEnCamino - inicio) / (fin - inicio);
        return minutoSalidaTramo + (long) Math.ceil(fraccion * (llegada - minutoSalidaTramo));
    }

    /**
     * Indice del nodo desde el que la unidad admite una ruta nueva.
     *
     * <p>Es la traduccion de la regla de que una unidad no puede darse la vuelta en mitad de
     * la calle: si esta detenida o justo sobre un nodo, es su propio nodo; si esta entre dos
     * nodos, es el proximo que alcanzara.</p>
     */
    public int indiceRedireccion(long minuto) {
        int actual = indiceNodoActual(minuto);
        if (indiceParada < 0 || sirviendo) {
            return actual;
        }
        int fin = desplazamientoParada[indiceParada];
        if (actual >= fin) {
            return fin;
        }
        return minutoLlegadaANodo(actual) >= minuto ? actual : actual + 1;
    }

    /** Nodo del camino en el indice dado. */
    public int nodoEn(int indiceEnCamino) {
        if (camino.length == 0) {
            return unidad.nodo();
        }
        int i = Math.max(0, Math.min(indiceEnCamino, camino.length - 1));
        return camino[i];
    }

    /** Nodo de la reticula en que se encuentra la unidad en el instante dado. */
    public int nodoActual(long minuto) {
        return nodoEn(indiceNodoActual(minuto));
    }

    /** Longitud del camino del itinerario vigente, en nodos. */
    public int longitudCamino() {
        return camino.length;
    }

    // ---------------------------------------------------------- kilometraje

    /** Ultimo indice del camino cuyo kilometraje ya se contabilizo. */
    public int indiceCobrado() {
        return indiceCobrado;
    }

    /**
     * Contabiliza los kilometros recorridos hasta el indice dado y devuelve cuantos eran.
     * Cada indice del camino se cobra una sola vez, de modo que una replanificacion a mitad
     * de tramo no duplica ni pierde kilometros.
     */
    public int cobrarHasta(int indiceEnCamino) {
        int destino = Math.max(indiceCobrado, Math.min(indiceEnCamino, camino.length - 1));
        int kilometros = destino - indiceCobrado;
        indiceCobrado = destino;
        return kilometros;
    }

    // -------------------------------------------------------------- eventos

    /** Secuencia del unico evento de movimiento vigente, o {@code -1} si no hay ninguno. */
    public long secuenciaMovimiento() {
        return secuenciaMovimiento;
    }

    /** Fija el evento de movimiento vigente y con ello invalida cualquier otro anterior. */
    public void secuenciaMovimiento(long secuencia) {
        this.secuenciaMovimiento = secuencia;
    }

    /** Invalida el evento de movimiento vigente sin retirarlo de la cola. */
    public void invalidarMovimiento() {
        this.secuenciaMovimiento = -1L;
    }

    /** Indica si el evento recibido sigue siendo el movimiento vigente de la unidad. */
    public boolean vigente(Evento evento) {
        return secuenciaMovimiento >= 0 && evento.secuencia() == secuenciaMovimiento;
    }

    // -------------------------------------------------------------- trasvase

    /** Indica si la unidad esta en camino de recoger la carga de una unidad averiada. */
    public boolean misionTrasvase() {
        return misionTrasvase;
    }

    /** Indice de la unidad averiada a la que asiste, o {@code -1}. */
    public int unidadAsistida() {
        return unidadAsistida;
    }

    /** Marca la mision de trasvase hacia la unidad averiada indicada. */
    public void marcarMisionTrasvase(int indiceUnidadAveriada) {
        this.misionTrasvase = true;
        this.unidadAsistida = indiceUnidadAveriada;
    }

    // ---------------------------------------------------------------- averia

    /** Averia que inmoviliza a la unidad, o {@code null} si esta operativa. */
    public TipoAveria averia() {
        return averia;
    }

    /** Acredita la pausa de alimentacion del turno que arranca en el instante dado. */
    public void acreditarPausa(long inicioTurno) {
        this.turnoPausaAcreditada = inicioTurno;
    }

    /** Indica si la unidad ya cumplio la pausa del turno que arranca en el instante dado. */
    public boolean pausaAcreditadaEn(long inicioTurno) {
        return turnoPausaAcreditada == inicioTurno;
    }

    /** Marca la unidad como detenida sin itinerario desde el instante dado, si no lo estaba ya. */
    public void marcarInactiva(long minuto) {
        if (inactivaDesde == Long.MIN_VALUE) {
            inactivaDesde = minuto;
        }
    }

    /** Marca la unidad como trabajando. */
    public void marcarActiva() {
        inactivaDesde = Long.MIN_VALUE;
    }

    /** Instante desde el que la unidad esta detenida sin itinerario, o MIN_VALUE si trabaja. */
    public long inactivaDesde() {
        return inactivaDesde;
    }

    public void averia(TipoAveria averia) {
        this.averia = averia;
    }

    // ------------------------------------------------------------- presentacion

    /** Nodos ya recorridos del itinerario vigente, como pares x,y consecutivos. */
    public int[] caminoRecorrido(long minuto) {
        return aPares(camino, 0, indiceNodoActual(minuto) + 1);
    }

    /** Nodos por recorrer del itinerario vigente, como pares x,y consecutivos. */
    public int[] caminoPendiente(long minuto) {
        return aPares(camino, indiceNodoActual(minuto), camino.length);
    }

    /** Convierte un rango de nodos en la lista de pares x,y que consume el visualizador. */
    public static int[] aPares(int[] nodos, int desde, int hasta) {
        int inicio = Math.max(0, desde);
        int fin = Math.min(nodos.length, hasta);
        if (fin <= inicio) {
            return new int[0];
        }
        int[] pares = new int[(fin - inicio) * 2];
        int k = 0;
        for (int i = inicio; i < fin; i++) {
            pares[k++] = Ciudad.x(nodos[i]);
            pares[k++] = Ciudad.y(nodos[i]);
        }
        return pares;
    }

    @Override
    public String toString() {
        return unidad.toString() + (misionTrasvase ? " [trasvase]" : "")
                + (averia != null ? " [averia " + averia.codigo() + "]" : "");
    }
}
