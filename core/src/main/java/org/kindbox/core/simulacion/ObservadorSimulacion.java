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

    /** Plan que el motor acaba de comprometer. Tratar la instancia como solo lectura. */
    default void alPublicarPlan(long minutoSimulado,
            org.kindbox.core.problema.InstanciaPlanificacion instancia,
            org.kindbox.core.metaheuristica.ResultadoPlanificacion plan) {
    }

    /** Descarga real, tambien parcial; pendiente es el remanente despues de descargar. */
    default void alDescargarPedido(long minutoSimulado, int idPedido, String codigoUnidad,
                                   int cantidad, int pendiente) {
    }

    /** Un pedido vencio sin completarse. No implica que haya colapsado la corrida. */
    default void alIncumplirPedido(long minutoSimulado, int idPedido, int cantidadNoEntregada) {
    }

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

    /** Motivos agrupados de la ejecucion que va a comenzar. */
    default void alIniciarReplanificacion(long minuto, String motivos) { }

    default void alDespacharRescate(long minuto, String averiada, String rescate,
            long llegadaPrevista, long finPrevisto, int cargaAveriada, int capacidadLibre) { }

    default void alNoAsignarRescate(long minuto, String averiada, String motivo) { }

    default void alIniciarTrasvase(long minuto, String averiada, String rescate, long finPrevisto) { }

    /** Movimiento de inventario entre unidades; no es una entrega al destinatario. */
    default void alCompletarTrasvase(long minuto, String averiada, String rescate,
            int cantidad, int cargaAveriada, int cargaRescate) { }

    /** Un almacen cambio de color de semaforo. */
    default void alCambiarSemaforo(long minutoSimulado, String nombreAlmacen,
                                   ColorSemaforo color, int disponible) {
    }

    /** La corrida termino, con el desenlace y el resumen. */
    default void alTerminar(ResultadoSimulacion resultado) {
    }
}
