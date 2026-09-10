package org.kindbox.service.simulacion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import org.kindbox.core.io.LectorAverias;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.EstadoCorrida;
import org.kindbox.core.simulacion.InstantaneaSimulacion;
import org.kindbox.core.simulacion.MotorSimulacion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.ResultadoSimulacion;

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

    private final String id;
    private final ConfiguracionEscenario configuracion;
    private final RepositorioDatos.DatosEscenario datos;
    private final int duracionMinutosReales;

    private final Set<Integer> entregados = ConcurrentHashMap.newKeySet();
    private final Deque<String> averiasAplicadas = new ArrayDeque<>();
    private final Queue<LectorAverias.AveriaProgramada> averiasProgramadas = new PriorityBlockingQueue<>();

    private volatile MotorSimulacion motor;
    private volatile EstadoCorrida estado = EstadoCorrida.PREPARADA;
    private volatile InstantaneaSimulacion instantanea;
    private volatile ResultadoSimulacion resultado;

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

    /** Identificadores de los pedidos ya entregados por completo. */
    public Set<Integer> entregados() {
        return entregados;
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
    public void alEntregarPedido(long minutoSimulado, int idPedido, String codigoUnidad,
                                 int cantidad, long minutosDesdeRegistro, int plazoHoras) {
        entregados.add(idPedido);
    }

    @Override
    public void alAveriarse(long minutoSimulado, String codigoUnidad, int tipoAveria,
                            int x, int y, int cargaABordo) {
        anotarAveria(datos.calendario().textoCorto(minutoSimulado) + " " + codigoUnidad
                + " tipo " + tipoAveria + " en (" + x + "," + y + ") con " + cargaABordo + " paquetes a bordo");
    }

    @Override
    public void alTerminar(ResultadoSimulacion resultadoFinal) {
        this.resultado = resultadoFinal;
        this.estado = resultadoFinal.estado();
    }
}
