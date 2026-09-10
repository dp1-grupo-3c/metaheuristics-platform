package org.kindbox.core.modelo;

/**
 * Unidad de transporte de la flota. El codigo sigue el formato TTNN de la respuesta 18
 * del cuestionario, donde TT es el prefijo del tipo y NN un correlativo.
 *
 * <p>Esta clase es el estado mutable que mantiene el motor de simulacion. El planificador
 * no la consume de forma directa: recibe una fotografia en arreglos primitivos dentro de
 * {@code InstanciaPlanificacion}.</p>
 */
public final class UnidadTransporte {

    private final String codigo;
    private final TipoUnidad tipo;

    private EstadoUnidad estado = EstadoUnidad.DISPONIBLE;
    private int nodo;
    private int cargaABordo;
    private long minutoDisponibleDesde;

    public UnidadTransporte(String codigo, TipoUnidad tipo, int nodoInicial) {
        this.codigo = codigo;
        this.tipo = tipo;
        this.nodo = nodoInicial;
    }

    /** Crea la unidad deduciendo el tipo del prefijo de su codigo. */
    public static UnidadTransporte de(String codigo, int nodoInicial) {
        return new UnidadTransporte(codigo, TipoUnidad.porCodigo(codigo), nodoInicial);
    }

    public String codigo() {
        return codigo;
    }

    public TipoUnidad tipo() {
        return tipo;
    }

    /** Capacidad nominal en paquetes. */
    public int capacidad() {
        return tipo.capacidad();
    }

    public EstadoUnidad estado() {
        return estado;
    }

    public void estado(EstadoUnidad estado) {
        this.estado = estado;
    }

    /** Nodo de la reticula en que se encuentra. */
    public int nodo() {
        return nodo;
    }

    public void nodo(int nodo) {
        this.nodo = nodo;
    }

    /** Paquetes del producto P que lleva a bordo. */
    public int cargaABordo() {
        return cargaABordo;
    }

    public void cargaABordo(int cargaABordo) {
        if (cargaABordo < 0 || cargaABordo > capacidad()) {
            throw new IllegalArgumentException(
                    "Carga " + cargaABordo + " fuera de la capacidad de " + codigo + " (" + capacidad() + ")");
        }
        this.cargaABordo = cargaABordo;
    }

    /** Capacidad libre a bordo. */
    public int capacidadLibre() {
        return capacidad() - cargaABordo;
    }

    /** Instante, en minutos desde el inicio del escenario, a partir del cual puede planificarse. */
    public long minutoDisponibleDesde() {
        return minutoDisponibleDesde;
    }

    public void minutoDisponibleDesde(long minuto) {
        this.minutoDisponibleDesde = minuto;
    }

    /** Indica si la unidad puede recibir ruta en el instante dado. */
    public boolean planificable(long minutoActual) {
        return estado != EstadoUnidad.AVERIADA
                && estado != EstadoUnidad.EN_MANTENIMIENTO
                && minutoDisponibleDesde <= minutoActual;
    }

    @Override
    public String toString() {
        return codigo + "[" + estado + " " + Ciudad.texto(nodo) + " carga=" + cargaABordo + "]";
    }
}
