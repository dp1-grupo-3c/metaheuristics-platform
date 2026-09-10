package org.kindbox.core.simulacion;

import java.util.PriorityQueue;

/**
 * Cola de prioridad de los sucesos del motor de simulacion.
 *
 * <p>El motor es dirigido por eventos y no por pasos de reloj, conforme al criterio que fija
 * {@link TipoEvento}: consume los datos del escenario en lugar de muestrear el tiempo, de
 * modo que un periodo sin actividad no cuesta nada. Esta clase es la estructura que sostiene
 * ese criterio.</p>
 *
 * <p>El orden lo fija {@link Evento#compareTo(Evento)}: primero el instante simulado, luego
 * el orden de declaracion de {@link TipoEvento} y por ultimo el numero de secuencia. Los dos
 * primeros criterios garantizan que dentro de un mismo minuto el entorno se actualice antes
 * de mover las unidades y que la replanificacion vea el estado ya actualizado; el tercero
 * garantiza un orden total y por tanto que dos corridas con la misma semilla produzcan la
 * misma traza.</p>
 *
 * <p>La cola <b>no</b> ofrece cancelacion de eventos ya encolados. La replanificacion
 * sustituye el plan completo cada {@code saltoMinutos} y dejaria obsoletos los eventos de
 * movimiento de todas las unidades, de modo que retirarlos uno a uno seria el trabajo
 * dominante del motor. En su lugar se invalidan de forma perezosa: cada unidad recuerda la
 * secuencia del unico evento de movimiento que tiene vigente y descarta cualquier otro que
 * le llegue. El costo de un evento obsoleto es una comparacion de enteros.</p>
 *
 * <p>La clase no es segura para uso concurrente: solo el hilo de la simulacion la toca.</p>
 */
public final class ColaEventos {

    private final PriorityQueue<Evento> cola = new PriorityQueue<>();
    private long secuencia;

    /**
     * Encola un suceso.
     *
     * @param minuto     instante simulado, en minutos desde el inicio del escenario
     * @param tipo       naturaleza del suceso
     * @param referencia indice del pedido, de la unidad o del bloqueo implicado, o {@code -1}
     * @param dato       carga util adicional, cuyo significado depende del tipo
     * @return el evento creado, cuyo numero de secuencia identifica de forma unica esta cita
     */
    public Evento programar(long minuto, TipoEvento tipo, int referencia, int dato) {
        Evento evento = new Evento(minuto, tipo, secuencia++, referencia, dato);
        cola.add(evento);
        return evento;
    }

    /** Encola un suceso que no implica a ningun elemento concreto. */
    public Evento programar(long minuto, TipoEvento tipo) {
        return programar(minuto, tipo, -1, 0);
    }

    /** Proximo suceso sin retirarlo, o {@code null} si la cola esta vacia. */
    public Evento asomar() {
        return cola.peek();
    }

    /** Retira y devuelve el proximo suceso, o {@code null} si la cola esta vacia. */
    public Evento siguiente() {
        return cola.poll();
    }

    /** Instante del proximo suceso, o {@link Long#MAX_VALUE} si ya no queda ninguno. */
    public long proximoMinuto() {
        Evento e = cola.peek();
        return e == null ? Long.MAX_VALUE : e.minuto();
    }

    /** Indica si no queda ningun suceso por procesar. */
    public boolean vacia() {
        return cola.isEmpty();
    }

    /** Sucesos aun encolados. */
    public int pendientes() {
        return cola.size();
    }

    /** Numero de secuencias emitidas, que es tambien el total de eventos creados. */
    public long secuenciasEmitidas() {
        return secuencia;
    }

    /** Vacia la cola. Se usa al abortar una corrida. */
    public void vaciar() {
        cola.clear();
    }
}
