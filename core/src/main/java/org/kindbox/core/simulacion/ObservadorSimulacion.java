package org.kindbox.core.simulacion;

/**
 * Receptor de los sucesos que el motor de simulacion publica.
 *
 * <p>Es el punto por el que el modulo de servicio se engancha al motor sin que este sepa
 * nada de HTTP ni de WebSocket: el motor empuja fotografias y avisos, y quien las
 * retransmite decide como. Todos los metodos tienen implementacion por defecto vacia, de
 * modo que un observador solo declare lo que le interesa.</p>
 *
 * <p>Los metodos se invocan desde el hilo de la simulacion. Una implementacion que haga
 * trabajo costoso debe encolarlo, porque bloquear aqui retrasa el reloj simulado.</p>
 */
public interface ObservadorSimulacion {

    /** Fotografia periodica del estado completo, segun la cadencia configurada. */
    default void alTomarFotografia(InstantaneaSimulacion instantanea) {
    }

    /** Una ejecucion del planificador ha terminado. */
    default void alReplanificar(long minutoSimulado, String algoritmo, int pedidosPendientes,
                                int pedidosNoAtendidos, double costo, long milisegundos) {
    }

    /** Un pedido se entrego por completo. */
    default void alEntregarPedido(long minutoSimulado, int idPedido, String codigoUnidad,
                                  int cantidad, long minutosDesdeRegistro, int plazoHoras) {
    }

    /** Una unidad se averio. */
    default void alAveriarse(long minutoSimulado, String codigoUnidad, int tipoAveria,
                             int x, int y, int cargaABordo) {
    }

    /** Un almacen cambio de color de semaforo. */
    default void alCambiarSemaforo(long minutoSimulado, String nombreAlmacen,
                                   ColorSemaforo color, int disponible) {
    }

    /** La corrida termino, con el desenlace y el resumen. */
    default void alTerminar(ResultadoSimulacion resultado) {
    }
}
