package org.kindbox.service.simulacion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import org.kindbox.core.io.LectorAverias;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.simulacion.ColorSemaforo;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.InstantaneaSimulacion;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;
import org.kindbox.service.dto.EventoCorrida;
import org.kindbox.service.dto.EventoCorrida.Tono;

/**
 * Una corrida de simulacion viva dentro del servicio: su configuracion, su motor, el
 * escenario del que salio y todo lo que el visualizador consulta sobre ella.
 *
 * <p>Es tambien el observador que sigue a su propio motor. De ahi salen dos cosas que la
 * fotografia no lleva y los paneles laterales necesitan: el conjunto de pedidos ya
 * entregados, con el que se arma la tabla de pedidos activos, y el registro de averias
 * aplicadas. Al ser el motor quien invoca esos metodos desde el hilo de la simulacion, todos
 * ellos se limitan a anotar en estructuras concurrentes y no hacen trabajo alguno.</p>
 */
public final class Corrida implements ObservadorSimulacion {

    /** Numero maximo de renglones que se conservan del registro de averias aplicadas. */
    private static final int MAXIMO_REGISTRO_AVERIAS = 200;
    /** Numero maximo de eventos de la bitacora; al superarlo se descartan los mas antiguos. */
    static final int MAXIMO_EVENTOS = 300;

    private final String id;
    private final ConfiguracionEscenario configuracion;
    private final RepositorioDatos.DatosEscenario datos;
    private final int duracionMinutosReales;

    // Pedido completado y minuto simulado en que se completo.
    private final Map<Integer, Long> entregados = new ConcurrentHashMap<>();
    // Unidades que descargaron cada pedido, en orden de descarga.
    private final Map<Integer, Set<String>> unidadesEntrega = new ConcurrentHashMap<>();
    private final Deque<String> averiasAplicadas = new ArrayDeque<>();
    private final Deque<EventoCorrida> eventos = new ArrayDeque<>();
    // Pedidos sin asignar de la ultima replanificacion; la bitacora anota solo los cambios.
    private int sinAsignarAnterior;
    private final Queue<LectorAverias.AveriaProgramada> averiasProgramadas = new PriorityBlockingQueue<>();

    private volatile MotorSimulacion motor;
    private volatile EstadoCorrida estado = EstadoCorrida.PREPARADA;
    private volatile InstantaneaSimulacion instantanea;
    private volatile ResultadoSimulacion resultado;
    private volatile String error;

    public Corrida(String id, ConfiguracionEscenario configuracion,
                   RepositorioDatos.DatosEscenario datos, int duracionMinutosReales) {
        this.id = id;
        this.configuracion = configuracion;
        this.datos = datos;
        this.duracionMinutosReales = duracionMinutosReales;
    }

    public String id() {
        return id;
    }

    public ConfiguracionEscenario configuracion() {
        return configuracion;
    }

    public RepositorioDatos.DatosEscenario datos() {
        return datos;
    }

    /** Duracion objetivo de la corrida en minutos de reloj real, que muestra la barra superior. */
    public int duracionMinutosReales() {
        return duracionMinutosReales;
    }

    public MotorSimulacion motor() {
        return motor;
    }

    void motor(MotorSimulacion motor) {
        this.motor = motor;
        this.instantanea = motor.instantanea();
    }

    public EstadoCorrida estado() {
        return estado;
    }

    void estado(EstadoCorrida estado) {
        this.estado = estado;
    }

    /** Ultima fotografia recibida del motor. */
    public InstantaneaSimulacion instantanea() {
        InstantaneaSimulacion vista = instantanea;
        if (vista == null && motor != null) {
            vista = motor.instantanea();
        }
        return vista;
    }

    /** Resumen final, o {@code null} si la corrida sigue en curso. */
    public ResultadoSimulacion resultado() {
        return resultado;
    }

    /** Mensaje funcional disponible cuando la corrida termina con fallo. */
    public String error() {
        return error;
    }

    void error(String error) {
        this.error = error;
    }

    /** Identificadores de los pedidos ya entregados por completo. */
    public Set<Integer> entregados() {
        return entregados.keySet();
    }

    /** Minuto simulado en que se completo el pedido, o {@code null} si aun no se entrega. */
    public Long minutoEntrega(int idPedido) {
        return entregados.get(idPedido);
    }

    /** Codigos de las unidades que descargaron el pedido, en orden de descarga. */
    public List<String> unidadesEntrega(int idPedido) {
        Set<String> unidades = unidadesEntrega.get(idPedido);
        if (unidades == null) {
            return List.of();
        }
        synchronized (unidades) {
            return List.copyOf(unidades);
        }
    }

    /** Averias que esperan a que el reloj simulado alcance su instante. */
    public Queue<LectorAverias.AveriaProgramada> averiasProgramadas() {
        return averiasProgramadas;
    }

    /** Registro legible de las averias ya aplicadas, de la mas reciente a la mas antigua. */
    public List<String> averiasAplicadas() {
        synchronized (averiasAplicadas) {
            return List.copyOf(averiasAplicadas);
        }
    }

    /** Anota una averia aplicada, recortando el registro para que no crezca sin limite. */
    public void anotarAveria(String renglon) {
        synchronized (averiasAplicadas) {
            averiasAplicadas.addFirst(renglon);
            while (averiasAplicadas.size() > MAXIMO_REGISTRO_AVERIAS) {
                averiasAplicadas.removeLast();
            }
        }
    }

    /** Bitacora de la corrida en orden cronologico, que el resumen final lista. */
    public List<EventoCorrida> eventos() {
        synchronized (eventos) {
            return List.copyOf(eventos);
        }
    }

    private void anotarEvento(long minutoSimulado, Tono tono, String mensaje) {
        EventoCorrida evento = new EventoCorrida(datos.calendario().aFecha(minutoSimulado), tono, mensaje);
        synchronized (eventos) {
            eventos.addLast(evento);
            while (eventos.size() > MAXIMO_EVENTOS) {
                eventos.removeFirst();
            }
        }
    }

    /** Indica si la corrida sigue viva y por tanto ocupa el turno de ejecucion. */
    public boolean enCurso() {
        return estado == EstadoCorrida.PREPARADA || estado == EstadoCorrida.EN_CURSO;
    }

    // ------------------------------------------------- observador del motor

    @Override
    public void alTomarFotografia(InstantaneaSimulacion nueva) {
        this.instantanea = nueva;
        if (enCurso()) {
            this.estado = nueva.estado();
        }
    }

    @Override
    public void alDescargarPedido(long minutoSimulado, int idPedido, String codigoUnidad,
                                  int cantidad, int pendiente) {
        Set<String> unidades = unidadesEntrega.computeIfAbsent(idPedido, k -> new LinkedHashSet<>());
        synchronized (unidades) {
            unidades.add(codigoUnidad);
        }
    }

    @Override
    public void alEntregarPedido(long minutoSimulado, int idPedido, String codigoUnidad,
                                 int cantidad, long minutosDesdeRegistro, int plazoHoras) {
        entregados.putIfAbsent(idPedido, minutoSimulado);
    }

    @Override
    public void alAveriarse(long minutoSimulado, String codigoUnidad, int tipoAveria,
                            int x, int y, int cargaABordo) {
        anotarAveria(datos.calendario().textoCorto(minutoSimulado) + " " + codigoUnidad
                + " tipo " + tipoAveria + " en (" + x + "," + y + ") con " + cargaABordo + " paquetes a bordo");
        anotarEvento(minutoSimulado, Tono.ROJO, "Avería tipo " + tipoAveria + " de " + codigoUnidad + " en ("
                + x + ", " + y + ") con " + paquetes(cargaABordo) + " a bordo");
    }

    @Override
    public void alReplanificar(long minutoSimulado, String algoritmo, int pedidosPendientes,
                               int pedidosNoAtendidos, double costo, long milisegundos) {
        int anterior = sinAsignarAnterior;
        sinAsignarAnterior = pedidosNoAtendidos;
        if (pedidosNoAtendidos > 0 && anterior == 0) {
            anotarEvento(minutoSimulado, Tono.AMBAR, "La planificación con " + algoritmo + " dejó "
                    + (pedidosNoAtendidos == 1 ? "1 pedido" : pedidosNoAtendidos + " pedidos") + " sin asignar");
        } else if (pedidosNoAtendidos == 0 && anterior > 0) {
            anotarEvento(minutoSimulado, Tono.VERDE, "La planificación volvió a asignar todos los pedidos");
        }
    }

    @Override
    public void alIncumplirPedido(long minutoSimulado, int idPedido, int cantidadNoEntregada) {
        anotarEvento(minutoSimulado, Tono.ROJO, "El pedido " + idPedido + " venció su plazo con "
                + paquetes(cantidadNoEntregada) + " sin entregar");
    }

    @Override
    public void alCambiarSemaforo(long minutoSimulado, String nombreAlmacen, ColorSemaforo color, int disponible) {
        String detalle = switch (color) {
            case VERDE -> "volvió a verde";
            case AMBAR -> "pasó a ámbar";
            case ROJO -> "pasó a rojo";
            case VACIO -> "se quedó sin stock";
        };
        Tono tono = switch (color) {
            case VERDE -> Tono.VERDE;
            case AMBAR -> Tono.AMBAR;
            case ROJO, VACIO -> Tono.ROJO;
        };
        anotarEvento(minutoSimulado, tono, nombreAlmacen + " " + detalle
                + (color == ColorSemaforo.VACIO ? "" : " con " + paquetes(disponible) + " disponibles"));
    }

    @Override
    public void alDespacharRescate(long minuto, String averiada, String rescate, long llegadaPrevista,
                                   long finPrevisto, int cargaAveriada, int capacidadLibre) {
        anotarEvento(minuto, Tono.AMBAR, rescate + " sale a recoger la carga de " + averiada);
    }

    @Override
    public void alNoAsignarRescate(long minuto, String averiada, String motivo) {
        anotarEvento(minuto, Tono.ROJO, "Ninguna unidad pudo recoger la carga de " + averiada);
    }

    @Override
    public void alCompletarTrasvase(long minuto, String averiada, String rescate, int cantidad,
                                    int cargaAveriada, int cargaRescate) {
        anotarEvento(minuto, Tono.VERDE, rescate + " recibió " + paquetes(cantidad) + " de " + averiada);
    }

    private static String paquetes(int cantidad) {
        return cantidad == 1 ? "1 paquete" : cantidad + " paquetes";
    }

    @Override
    public void alTerminar(ResultadoSimulacion resultadoFinal) {
        String mensaje = switch (resultadoFinal.estado()) {
            case CULMINADA -> "La simulación culminó con éxito";
            case COLAPSADA -> "Colapso de operaciones";
            case CANCELADA -> "La simulación fue cancelada";
            case DATOS_AGOTADOS -> "Se agotaron los datos de ventas";
            default -> "La simulación se detuvo por un error";
        };
        Tono tono = switch (resultadoFinal.estado()) {
            case CULMINADA -> Tono.VERDE;
            case CANCELADA, DATOS_AGOTADOS -> Tono.AMBAR;
            default -> Tono.ROJO;
        };
        anotarEvento(resultadoFinal.minutoFinal(), tono, mensaje);
        this.resultado = resultadoFinal;
        this.estado = resultadoFinal.estado();
    }
}
