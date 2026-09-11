package org.kindbox.core.simulacion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.kindbox.core.grafo.MatrizDistancias;
import org.kindbox.core.grafo.MatrizDistanciasReticula;
import org.kindbox.core.grafo.OraculoDistancias;
import org.kindbox.core.grafo.RegistroBloqueos;
import org.kindbox.core.io.CalendarioEscenario;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.PresupuestoComputo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.Almacen;
import org.kindbox.core.modelo.Averia;
import org.kindbox.core.modelo.Bloqueo;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.Mantenimiento;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.kindbox.core.modelo.Pedido;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.modelo.TipoUnidad;
import org.kindbox.core.modelo.Turno;
import org.kindbox.core.modelo.UnidadTransporte;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.problema.Parada;
import org.kindbox.core.problema.Ruta;
import org.kindbox.core.problema.Solucion;
import org.kindbox.core.problema.TipoParada;
import org.kindbox.core.problema.ValorObjetivo;
import org.kindbox.core.util.Aleatorio;

/**
 * Motor de simulacion dirigido por eventos de PaqRap.
 *
 * <p>Es el componente que hace correr el tiempo del prototipo. Consume los datos del
 * escenario (pedidos, bloqueos, mantenimiento preventivo y flota) en lugar de muestrear el
 * reloj, de modo que un periodo sin actividad no cuesta nada, y publica lo que ocurre a
 * traves de {@link ObservadorSimulacion}, sin saber nada de HTTP ni de WebSocket.</p>
 *
 * <h2>Bucle principal</h2>
 * <p>Una cola de prioridad de {@link Evento} ordena los sucesos por instante simulado y, a
 * igualdad de instante, por el orden de declaracion de {@link TipoEvento}. Ese orden fija de
 * forma deliberada que el entorno se actualice antes de mover las unidades y que la
 * replanificacion vea ya el estado actualizado. En modo {@link ModoReloj#ACOMPASADO} el
 * motor espera, antes de procesar cada suceso, a que el reloj de pared alcance el instante
 * simulado segun {@link ConfiguracionEscenario#milisegundosPorMinutoSimulado()}; la espera
 * es interrumpible para que {@link #cancelar()} termine la corrida sin demora.</p>
 *
 * <h2>Replanificacion</h2>
 * <p>Cada {@code saltoMinutos} el motor ejecuta el planificador <b>completo</b> sobre la
 * totalidad de los pedidos pendientes y con todas las unidades planificables, tal como exige
 * el apartado 2.2 del ISA: el plan no se modifica de forma incremental, se rehace, y en el
 * entran tanto los vehiculos que estan en almacen como los que estan en ruta. Los parametros
 * de operacion se congelan al arrancar la iteracion con
 * {@link ParametrosOperacion#instantanea()}, que es la semantica de la respuesta 16 del
 * cuestionario: un cambio de velocidad en caliente se aplica a partir de la iteracion
 * siguiente. La matriz de distancias se reconstruye con la mascara de bloqueos vigente en
 * ese instante, y la asignacion del plan que se abandona viaja al constructor de la
 * instancia para alimentar el termino de estabilidad del apartado 11.4. Si la configuracion
 * activa {@code arranqueDesdePlanVigente} y el algoritmo lo admite, ese plan viaja ademas
 * entero, recortado a la fotografia, como solucion de partida de la busqueda: es el segundo
 * modo de arranque del apartado 7.3.5 y la hipotesis experimental del apartado 11.4.</p>
 *
 * <p>Una unidad sorprendida a mitad de un tramo <b>no puede darse la vuelta en mitad de la
 * calle</b>. Por eso se planifica desde el proximo nodo que alcanzara, con
 * {@code minutoDisponibleDesde} igual al instante en que llegara a el, y el kilometro que le
 * falta para llegar se incorpora como cabecera del camino nuevo, de modo que se dibuje y se
 * cobre como cualquier otro. Una unidad detenida en un servicio (la hora de acondicionamiento
 * del producto o la pausa de alimentacion) tampoco se interrumpe: se planifica desde su nodo
 * con disponibilidad al cierre de ese servicio.</p>
 *
 * <h2>Ejecucion de rutas</h2>
 * <p>El camino nodo a nodo de cada tramo se obtiene de {@code matriz.camino(a,b)}, es decir
 * del mismo objeto con que el planificador costeo la ruta, y no se recalcula. Asi el trazo
 * que ve el visualizador y los kilometros que se cobran son exactamente los que el plan
 * supuso. Como la reticula tiene un nodo por kilometro, el kilometraje se contabiliza
 * contando indices del camino, lo que hace que una replanificacion a mitad de tramo no
 * duplique ni pierda kilometros.</p>
 *
 * <h2>Colapso logistico</h2>
 * <p>Se produce en el primer instante en que un pedido pendiente supera su instante limite
 * sin haberse completado. En {@link TipoEscenario#COLAPSO} la corrida termina ahi con estado
 * {@link EstadoCorrida#COLAPSADA} y ese instante es la metrica del apartado 12.1. En
 * {@link TipoEscenario#SIMULACION_5D} y {@link TipoEscenario#DIA_A_DIA} el incumplimiento se
 * contabiliza en {@code MetricasSimulacion.pedidosIncumplidos} y la corrida continua hasta
 * agotar el horizonte, porque lo que ahi se mide es el desempeno de la operacion completa y
 * no cuanto tarda en romperse. El prototipo contempla, sin embargo, el desenlace "fracasado
 * por colapso de operaciones" tambien en la 5D, de modo que
 * {@link #umbralIncumplimientos(int)} expone el numero de incumplimientos a partir del cual
 * la 5D aborta igualmente; por defecto no aborta nunca.</p>
 *
 * <h2>Concurrencia</h2>
 * <p>{@link #ejecutar()} corre en el hilo que lo invoca. {@link #cancelar()},
 * {@link #instantanea()}, {@link #registrarAveria(String, TipoAveria)} y los cambios sobre
 * {@link #parametros()} pueden llegar de otro hilo: el estado del mundo se lee y se escribe
 * bajo un candado que el bucle toma para cada suceso y suelta mientras espera al reloj de
 * pared o mientras el planificador trabaja, de modo que una fotografia nunca captura un
 * estado a medio actualizar ni bloquea al visualizador durante una iteracion completa del
 * planificador.</p>
 */
public final class MotorSimulacion {

    /** Valor de {@link #umbralIncumplimientos(int)} que desactiva el aborto por incumplimientos. */
    public static final int SIN_UMBRAL_INCUMPLIMIENTOS = Integer.MAX_VALUE;

    /** Marca de {@code dato} de un evento de movimiento que corresponde a una mision de trasvase. */
    private static final int PARADA_TRASVASE = 0;
    /** Espera maxima de un solo bloqueo en modo acompasado, para que la cancelacion sea inmediata. */
    private static final long MAXIMA_ESPERA_MS = 200L;

    private final RepositorioDatos.DatosEscenario datos;
    private final ConfiguracionEscenario configuracion;
    private final ParametrosOperacion parametros;
    private final Algoritmo algoritmo;
    private final List<ObservadorSimulacion> observadores;

    private final CalendarioEscenario calendario;
    private final EstadoSimulacion estado;
    private final ColaEventos cola = new ColaEventos();
    private final GeneradorAverias generadorAverias;
    private final OraculoDistancias oraculo = new OraculoDistancias();
    private final int[] distanciasAuxiliares = new int[Ciudad.TOTAL_NODOS];
    private final int[] predecesoresAuxiliares = new int[Ciudad.TOTAL_NODOS];
    private final long minutoFin;

    private final ReentrantLock candado = new ReentrantLock();
    private final Object monitorEspera = new Object();
    private final Queue<int[]> averiasManuales = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean arrancada = new AtomicBoolean();

    private volatile long minutoActual;
    private volatile EstadoCorrida estadoCorrida = EstadoCorrida.PREPARADA;
    private volatile boolean cancelado;
    private volatile PresupuestoComputo presupuestoVigente;
    private volatile int umbralIncumplimientos = SIN_UMBRAL_INCUMPLIMIENTOS;
    private volatile ResultadoSimulacion resultado;
    private volatile ParametrosOperacion.Instantanea parametrosVigentes;

    private long nanoInicio;
    private long minutoPrimerIncumplimiento = -1L;
    private int pedidoDelPrimerIncumplimiento = -1;
    private long minutoReplanificacionPorBloqueo = Long.MIN_VALUE;
    /**
     * Replanificaciones lanzadas en la corrida. Es el numero de orden del que se deriva la
     * semilla de cada iteracion del planificador; solo lo toca el hilo de la simulacion.
     */
    private long replanificacionesLanzadas;

    private int[] caminoTrabajo = new int[512];
    private int longitudTrabajo;

    /**
     * Construye el motor.
     *
     * @param datos         escenario cargado del disco por {@link RepositorioDatos}
     * @param configuracion parametros de la corrida
     * @param parametros    parametros de operacion modificables en caliente
     * @param algoritmo     planificador que se ejecuta en cada iteracion
     * @param observadores  receptores de fotografias y avisos; puede ir vacia
     */
    public MotorSimulacion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores) {
        if (datos == null || configuracion == null || parametros == null || algoritmo == null) {
            throw new IllegalArgumentException("El motor necesita datos, configuracion, parametros y algoritmo");
        }
        this.datos = datos;
        this.configuracion = configuracion;
        this.parametros = parametros;
        this.algoritmo = algoritmo;
        this.observadores = new CopyOnWriteArrayList<>(observadores == null ? List.of() : observadores);
        this.calendario = datos.calendario();
        this.generadorAverias = GeneradorAverias.deConfiguracion(configuracion);
        this.parametrosVigentes = parametros.instantanea();

        RegistroBloqueos bloqueos = new RegistroBloqueos(datos.bloqueos());
        this.estado = new EstadoSimulacion(calendario, bloqueos, Almacen.todos(), datos.pedidos(), datos.unidades());

        long cierreConfigurado = calendario.aMinutos(configuracion.ultimoDia().plusDays(1).atStartOfDay());
        this.minutoFin = Math.min(cierreConfigurado, datos.minutoFin());
        if (minutoFin <= 0) {
            throw new IllegalArgumentException("El horizonte del escenario es vacio: el ultimo dia configurado ("
                    + configuracion.ultimoDia() + ") no alcanza el primer dia de los datos (" + datos.primerDia() + ")");
        }
        sembrar();
    }

    // ------------------------------------------------------- interfaz publica

    /** Configuracion con la que se ejecuta la corrida. */
    public ConfiguracionEscenario configuracion() {
        return configuracion;
    }

    /**
     * Parametros de operacion modificables en caliente. El servicio REST los cambia desde
     * otro hilo; el valor nuevo rige a partir de la siguiente iteracion de planificacion.
     */
    public ParametrosOperacion parametros() {
        return parametros;
    }

    /** Situacion de la corrida. */
    public EstadoCorrida estadoCorrida() {
        return estadoCorrida;
    }

    /** Instante simulado alcanzado, en minutos desde el inicio del escenario. */
    public long minutoActual() {
        return minutoActual;
    }

    /** Ultimo instante simulado del horizonte. */
    public long minutoFin() {
        return minutoFin;
    }

    /** Resumen de la corrida, o {@code null} mientras no ha terminado. */
    public ResultadoSimulacion resultado() {
        return resultado;
    }

    /**
     * Numero de incumplimientos a partir del cual una corrida 5D o dia a dia aborta con
     * estado {@link EstadoCorrida#COLAPSADA}. Por defecto {@link #SIN_UMBRAL_INCUMPLIMIENTOS},
     * es decir la corrida agota siempre el horizonte.
     */
    public void umbralIncumplimientos(int incumplimientos) {
        if (incumplimientos <= 0) {
            throw new IllegalArgumentException("El umbral de incumplimientos debe ser positivo");
        }
        this.umbralIncumplimientos = incumplimientos;
    }

    /** Umbral de incumplimientos vigente. */
    public int umbralIncumplimientos() {
        return umbralIncumplimientos;
    }

    /** Incorpora un observador a una corrida ya construida. */
    public void agregarObservador(ObservadorSimulacion observador) {
        if (observador != null) {
            observadores.add(observador);
        }
    }

    /** Retira un observador. */
    public void quitarObservador(ObservadorSimulacion observador) {
        observadores.remove(observador);
    }

    /**
     * Corre la simulacion hasta agotar el horizonte, hasta el colapso logistico o hasta que
     * otro hilo invoque {@link #cancelar()}.
     *
     * <p>Un fallo interno se recoge como estado {@link EstadoCorrida#FALLIDA}: el resumen
     * queda publicado en {@link #resultado()} y los observadores reciben su aviso de cierre,
     * porque el visualizador tiene que poder decir que paso. Un {@link Error} se vuelve a
     * lanzar despues de publicarlo, porque no es una condicion de la que el proceso pueda
     * recuperarse y tragarselo solo esconderia el problema.</p>
     */
    public ResultadoSimulacion ejecutar() {
        if (!arrancada.compareAndSet(false, true)) {
            throw new IllegalStateException("La corrida ya fue ejecutada");
        }
        nanoInicio = System.nanoTime();
        estadoCorrida = EstadoCorrida.EN_CURSO;
        String mensaje;
        Error fatal = null;
        try {
            mensaje = bucle();
        } catch (RuntimeException e) {
            estadoCorrida = EstadoCorrida.FALLIDA;
            mensaje = "Error interno en " + calendario.textoCorto(minutoActual) + ": " + e;
        } catch (Error e) {
            estadoCorrida = EstadoCorrida.FALLIDA;
            mensaje = "Error irrecuperable en " + calendario.textoCorto(minutoActual) + ": " + e;
            fatal = e;
        }
        ResultadoSimulacion resumen;
        candado.lock();
        try {
            resumen = new ResultadoSimulacion(configuracion, estadoCorrida, minutoActual,
                    calendario.aFecha(minutoActual), milisegundosReales(), estado.metricas(),
                    minutoPrimerIncumplimiento, pedidoDelPrimerIncumplimiento, mensaje);
        } finally {
            candado.unlock();
        }
        this.resultado = resumen;
        notificar(o -> o.alTerminar(resumen));
        if (fatal != null) {
            throw fatal;
        }
        return resumen;
    }

    /**
     * Detiene la corrida desde otro hilo. La iteracion del planificador en curso se
     * interrumpe por presupuesto y el bucle termina de forma limpia con estado
     * {@link EstadoCorrida#CANCELADA}.
     */
    public void cancelar() {
        this.cancelado = true;
        PresupuestoComputo presupuesto = presupuestoVigente;
        if (presupuesto != null) {
            presupuesto.cancelar();
        }
        synchronized (monitorEspera) {
            monitorEspera.notifyAll();
        }
    }

    /** Indica si se solicito la cancelacion de la corrida. */
    public boolean cancelado() {
        return cancelado;
    }

    /**
     * Fotografia completa del estado actual. Es segura de invocar desde otro hilo: se
     * construye bajo el candado del motor, de modo que nunca captura un estado a medio
     * actualizar.
     */
    public InstantaneaSimulacion instantanea() {
        candado.lock();
        try {
            return construirInstantanea();
        } finally {
            candado.unlock();
        }
    }

    /**
     * Registra una averia a mano desde el visualizador. Se aplica en el instante simulado
     * actual y, a diferencia de las que genera el motor por reglas, se aplica tambien si la
     * unidad esta detenida en un almacen: quien la registra esta afirmando que la unidad no
     * esta disponible.
     *
     * @param codigoUnidad codigo TTNN de la unidad averiada
     * @param tipo         gravedad de la averia
     */
    public void registrarAveria(String codigoUnidad, TipoAveria tipo) {
        if (tipo == null) {
            throw new IllegalArgumentException("La averia necesita un tipo");
        }
        int indice = estado.indiceDeUnidad(codigoUnidad);
        if (indice < 0) {
            throw new IllegalArgumentException("La flota no tiene ninguna unidad con codigo " + codigoUnidad);
        }
        averiasManuales.add(new int[] {indice, tipo.codigo()});
        synchronized (monitorEspera) {
            monitorEspera.notifyAll();
        }
    }

    /** Estado del mundo simulado, para consultas de solo lectura del modulo de servicio. */
    public EstadoSimulacion estado() {
        return estado;
    }

    // ---------------------------------------------------------------- siembra

    /**
     * Encola los sucesos que ya se conocen antes de arrancar: la llegada de cada pedido, la
     * activacion y desactivacion de cada bloqueo, el inicio y el fin de cada mantenimiento
     * preventivo, la recarga diaria de los almacenes intermedios, los cambios de turno, las
     * replanificaciones periodicas, las fotografias y el fin del escenario.
     */
    private void sembrar() {
        List<Pedido> pedidos = estado.pedidos();
        for (int i = 0; i < pedidos.size(); i++) {
            long registro = pedidos.get(i).minutoRegistro();
            if (registro >= 0 && registro <= minutoFin) {
                cola.programar(registro, TipoEvento.LLEGADA_PEDIDO, i, 0);
            }
        }

        List<Bloqueo> bloqueos = datos.bloqueos();
        for (int i = 0; i < bloqueos.size(); i++) {
            Bloqueo b = bloqueos.get(i);
            if (b.minutoInicio() >= 0 && b.minutoInicio() <= minutoFin) {
                cola.programar(b.minutoInicio(), TipoEvento.ACTIVACION_BLOQUEO, i, 0);
            }
            if (b.minutoFin() >= 0 && b.minutoFin() <= minutoFin) {
                cola.programar(b.minutoFin(), TipoEvento.DESACTIVACION_BLOQUEO, i, 0);
            }
        }

        for (Mantenimiento m : datos.mantenimientos()) {
            int unidad = estado.indiceDeUnidad(m.codigoUnidad());
            if (unidad < 0) {
                continue;
            }
            long inicio = calendario.aMinutos(m.fecha());
            if (inicio > minutoFin) {
                continue;
            }
            cola.programar(Math.max(0L, inicio), TipoEvento.INICIO_MANTENIMIENTO, unidad, 0);
            cola.programar(Math.min(minutoFin, inicio + Almacen.MINUTO_RECARGA_DIARIA),
                    TipoEvento.FIN_MANTENIMIENTO, unidad, 0);
        }

        for (long dia = 0; dia * CalendarioEscenario.MINUTOS_POR_DIA <= minutoFin; dia++) {
            long base = dia * CalendarioEscenario.MINUTOS_POR_DIA;
            long recarga = base + Almacen.MINUTO_RECARGA_DIARIA;
            if (recarga <= minutoFin) {
                cola.programar(recarga, TipoEvento.RECARGA_ALMACENES);
            }
            for (int cambio : Turno.CAMBIOS_DE_TURNO) {
                if (base + cambio <= minutoFin) {
                    cola.programar(base + cambio, TipoEvento.CAMBIO_TURNO);
                }
            }
        }

        for (long minuto = 0; minuto < minutoFin; minuto += configuracion.saltoMinutos()) {
            cola.programar(minuto, TipoEvento.REPLANIFICACION);
        }
        for (long minuto = 0; minuto <= minutoFin; minuto += configuracion.minutosEntreFotografias()) {
            cola.programar(minuto, TipoEvento.FOTOGRAFIA);
        }
        cola.programar(minutoFin, TipoEvento.FIN_ESCENARIO);
    }

    // ----------------------------------------------------------- bucle principal

    /** Bucle de eventos. Devuelve el mensaje que explica el desenlace. */
    private String bucle() {
        while (true) {
            if (cancelado) {
                estadoCorrida = EstadoCorrida.CANCELADA;
                return "Corrida cancelada por el usuario en " + calendario.textoCorto(minutoActual);
            }
            drenarAveriasManuales();

            long minutoVencimiento = vencimientoPendiente();
            Evento proximo = cola.asomar();
            if (proximo == null && minutoVencimiento == Long.MAX_VALUE) {
                estadoCorrida = EstadoCorrida.CULMINADA;
                return "Escenario agotado en " + calendario.textoCorto(minutoActual);
            }
            long objetivo = Math.min(proximo == null ? Long.MAX_VALUE : proximo.minuto(), minutoVencimiento);
            if (!esperarReloj(objetivo)) {
                estadoCorrida = EstadoCorrida.CANCELADA;
                return "Corrida cancelada por el usuario en " + calendario.textoCorto(minutoActual);
            }
            if (drenarAveriasManuales()) {
                continue;
            }

            // El vencimiento de un plazo solo se resuelve cuando ya no queda ningun suceso en
            // ese mismo minuto: una entrega que llega justo en el instante limite cumple.
            if (minutoVencimiento != Long.MAX_VALUE
                    && (proximo == null || minutoVencimiento < proximo.minuto())) {
                minutoActual = Math.max(minutoActual, minutoVencimiento);
                String desenlace = resolverVencimientos();
                if (desenlace != null) {
                    return desenlace;
                }
                continue;
            }

            Evento evento = cola.siguiente();
            minutoActual = evento.minuto();
            String desenlace = procesar(evento);
            if (desenlace != null) {
                return desenlace;
            }
        }
    }

    /** Instante limite del pedido pendiente mas urgente, leido bajo el candado. */
    private long vencimientoPendiente() {
        candado.lock();
        try {
            return estado.proximoVencimiento();
        } finally {
            candado.unlock();
        }
    }

    /**
     * Espera a que el reloj de pared alcance el instante simulado indicado. En modo
     * {@link ModoReloj#LIBRE} no espera. Devuelve {@code false} si la corrida fue cancelada.
     */
    private boolean esperarReloj(long minutoObjetivo) {
        if (configuracion.modoReloj() == ModoReloj.LIBRE) {
            return !cancelado;
        }
        double milisegundosPorMinuto = configuracion.milisegundosPorMinutoSimulado();
        while (!cancelado) {
            long objetivo = (long) (minutoObjetivo * milisegundosPorMinuto);
            long restante = objetivo - milisegundosReales();
            if (restante <= 0) {
                return true;
            }
            synchronized (monitorEspera) {
                if (cancelado) {
                    return false;
                }
                try {
                    monitorEspera.wait(Math.min(restante, MAXIMA_ESPERA_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelado = true;
                    return false;
                }
            }
            if (!averiasManuales.isEmpty()) {
                // La averia registrada a mano se atiende en el instante simulado actual, sin
                // agotar la espera hacia el proximo suceso.
                return true;
            }
        }
        return false;
    }

    /**
     * Traslada al motor las averias registradas desde el visualizador. Devuelve {@code true}
     * si encolo alguna, de modo que el bucle reevalue la cola antes de seguir.
     */
    private boolean drenarAveriasManuales() {
        boolean alguna = false;
        int[] manual;
        while ((manual = averiasManuales.poll()) != null) {
            // El signo negativo del dato distingue la averia registrada a mano de la generada
            // por reglas: la primera se aplica aunque la unidad este parada en un almacen.
            cola.programar(minutoActual, TipoEvento.AVERIA, manual[0], -manual[1]);
            alguna = true;
        }
        return alguna;
    }

    /** Milisegundos de reloj de pared transcurridos desde el arranque de la corrida. */
    private long milisegundosReales() {
        return nanoInicio == 0L ? 0L : (System.nanoTime() - nanoInicio) / 1_000_000L;
    }

    // -------------------------------------------------------- proceso de eventos

    /** Procesa un suceso. Devuelve el mensaje de desenlace si la corrida debe terminar. */
    private String procesar(Evento evento) {
        switch (evento.tipo()) {
            case REPLANIFICACION -> {
                replanificar();
                return null;
            }
            case FOTOGRAFIA -> {
                fotografiar();
                return null;
            }
            case FIN_ESCENARIO -> {
                estadoCorrida = EstadoCorrida.CULMINADA;
                return "Horizonte completado en " + calendario.textoCorto(minutoActual);
            }
            default -> {
                // El resto de los sucesos solo tocan el estado del mundo.
            }
        }
        candado.lock();
        try {
            switch (evento.tipo()) {
                case RECARGA_ALMACENES -> recargarAlmacenes();
                case ACTIVACION_BLOQUEO, DESACTIVACION_BLOQUEO -> replanificarPorBloqueo();
                case CAMBIO_TURNO -> sortearAveriasDelTurno();
                case INICIO_MANTENIMIENTO -> iniciarMantenimiento(evento.referencia());
                case FIN_MANTENIMIENTO -> terminarMantenimiento(evento.referencia());
                case LLEGADA_PEDIDO -> estado.registrarPedido(evento.referencia());
                case AVERIA -> averiar(evento.referencia(), evento.dato());
                case TRASLADO_A_CENTRAL -> trasladarAlCentral(evento.referencia());
                case FIN_AVERIA -> reincorporar(evento.referencia());
                case LLEGADA_A_PARADA -> llegarAParada(evento);
                case FIN_SERVICIO -> terminarServicio(evento);
                default -> throw new IllegalStateException("Evento no contemplado: " + evento.tipo());
            }
        } finally {
            candado.unlock();
        }
        return null;
    }

    /**
     * Marca como incumplidos los pedidos cuyo plazo acaba de vencer y decide si la corrida
     * termina por colapso logistico.
     */
    private String resolverVencimientos() {
        List<Integer> vencidos;
        int incumplidos;
        candado.lock();
        try {
            vencidos = estado.vencidosHasta(minutoActual);
            incumplidos = estado.pedidosIncumplidos();
        } finally {
            candado.unlock();
        }
        if (vencidos.isEmpty()) {
            return null;
        }
        if (minutoPrimerIncumplimiento < 0) {
            minutoPrimerIncumplimiento = estado.pedido(vencidos.get(0)).minutoLimite();
            pedidoDelPrimerIncumplimiento = estado.pedido(vencidos.get(0)).id();
        }
        if (configuracion.tipo() == TipoEscenario.COLAPSO) {
            estadoCorrida = EstadoCorrida.COLAPSADA;
            return "Colapso logistico en " + calendario.textoCorto(minutoPrimerIncumplimiento)
                    + ": el pedido " + pedidoDelPrimerIncumplimiento + " no pudo entregarse dentro de su plazo";
        }
        if (incumplidos >= umbralIncumplimientos) {
            estadoCorrida = EstadoCorrida.COLAPSADA;
            return "Operaciones colapsadas en " + calendario.textoCorto(minutoActual) + ": "
                    + incumplidos + " pedidos incumplidos superan el umbral configurado de "
                    + umbralIncumplimientos;
        }
        return null;
    }

    // ------------------------------------------------------------- entorno

    /** Recarga a capacidad plena los almacenes intermedios y revisa sus semaforos. */
    private void recargarAlmacenes() {
        estado.recargarIntermedios();
        for (int i = 0; i < estado.almacenes().size(); i++) {
            revisarSemaforo(i);
        }
    }

    /**
     * La red de calles cambio. El plan vigente se costeo sobre una red que ya no existe, de
     * modo que se adelanta una replanificacion a este mismo instante, conforme al criterio de
     * {@code MatrizDistancias}: la matriz se recalcula ante la activacion o desactivacion de
     * un bloqueo. La replanificacion se encola en lugar de ejecutarse aqui para que el orden
     * de {@link TipoEvento} siga rigiendo: primero termina de actualizarse el entorno.
     */
    private void replanificarPorBloqueo() {
        // Una poligonal larga produce varios sucesos en el mismo minuto y un bloqueo puede
        // caer justo en la cadencia periodica: en ambos casos ya hay una replanificacion
        // encolada para este instante y anadir otra solo gastaria presupuesto.
        if (minutoActual % configuracion.saltoMinutos() == 0
                || minutoReplanificacionPorBloqueo == minutoActual) {
            return;
        }
        minutoReplanificacionPorBloqueo = minutoActual;
        cola.programar(minutoActual, TipoEvento.REPLANIFICACION);
    }

    /** Recalcula el semaforo de un almacen y publica el cambio si lo hubo. */
    private void revisarSemaforo(int indiceAlmacen) {
        ColorSemaforo nuevo = estado.actualizarSemaforo(indiceAlmacen, parametrosVigentes);
        if (nuevo == null) {
            return;
        }
        String nombre = estado.almacenes().get(indiceAlmacen).nombre();
        int disponible = estado.inventario(indiceAlmacen);
        if (nuevo != ColorSemaforo.VERDE) {
            estado.anotarActivacionSemaforo(minutoActual, indiceAlmacen, nuevo);
        }
        long minuto = minutoActual;
        notificar(o -> o.alCambiarSemaforo(minuto, nombre, nuevo, disponible));
    }

    // ------------------------------------------------------- mantenimiento

    /**
     * Nota 2 de la respuesta 19 del cuestionario: si la unidad esta en ruta al iniciar su
     * mantenimiento debe retornar de inmediato a las 00:00, aunque no haya entregado un
     * pedido. Los paquetes que llevaba vuelven al almacen central, de inventario ilimitado, y
     * los pedidos que iba a atender siguen en el conjunto de pendientes, porque un pedido solo
     * sale de el cuando se completa o cuando vence su plazo.
     */
    private void iniciarMantenimiento(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        cobrarKilometros(u, u.indiceNodoActual(minutoActual));
        u.invalidarMovimiento();
        u.limpiarItinerario(estado.central().nodo());
        u.averia(null);
        UnidadTransporte unidad = u.unidad();
        unidad.nodo(estado.central().nodo());
        unidad.cargaABordo(0);
        unidad.estado(EstadoUnidad.EN_MANTENIMIENTO);
        unidad.minutoDisponibleDesde(minutoActual + Almacen.MINUTO_RECARGA_DIARIA);
    }

    /** La unidad vuelve a estar disponible a las 23:59 del dia programado. */
    private void terminarMantenimiento(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        if (u.unidad().estado() != EstadoUnidad.EN_MANTENIMIENTO) {
            return;
        }
        u.unidad().estado(EstadoUnidad.DISPONIBLE);
        u.unidad().minutoDisponibleDesde(minutoActual);
    }

    // -------------------------------------------------------------- averias

    /**
     * Sortea, al arrancar un turno, que unidades sufriran una averia durante el y en que
     * instante. Las reglas viven en {@link GeneradorAverias}, que las deriva de la semilla de
     * la corrida por unidad y por turno y por tanto no depende del orden en que se consulte
     * la flota. Aqui solo se traducen los incidentes fechados en eventos de la cola.
     */
    private void sortearAveriasDelTurno() {
        if (!generadorAverias.activo()) {
            return;
        }
        List<UnidadTransporte> candidatas = new ArrayList<>(estado.unidades().length);
        for (UnidadEnCurso u : estado.unidades()) {
            if (u.averia() == null && u.unidad().estado() != EstadoUnidad.EN_MANTENIMIENTO) {
                candidatas.add(u.unidad());
            }
        }
        for (Averia averia : generadorAverias.averiasDelTurno(minutoActual, candidatas)) {
            int indice = estado.indiceDeUnidad(averia.codigoUnidad());
            if (indice < 0 || averia.minutoAveria() > minutoFin) {
                continue;
            }
            cola.programar(Math.max(minutoActual, averia.minutoAveria()),
                    TipoEvento.AVERIA, indice, averia.tipo().codigo());
        }
    }

    /**
     * Inmoviliza una unidad.
     *
     * @param dato codigo de la gravedad; negativo si la averia se registro a mano desde el
     *             visualizador, en cuyo caso se aplica aunque la unidad no este en operacion
     */
    private void averiar(int indiceUnidad, int dato) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        boolean manual = dato < 0;
        TipoAveria tipo = TipoAveria.porCodigo(Math.abs(dato));
        if (u.averia() != null || u.unidad().estado() == EstadoUnidad.EN_MANTENIMIENTO) {
            return;
        }
        if (!manual && !enOperacion(u)) {
            return;
        }

        int indiceNodo = u.indiceNodoActual(minutoActual);
        cobrarKilometros(u, indiceNodo);
        int nodo = u.nodoEn(indiceNodo);
        u.invalidarMovimiento();
        int cargaABordo = u.unidad().cargaABordo();
        u.limpiarItinerario(nodo);
        u.averia(tipo);
        UnidadTransporte unidad = u.unidad();
        unidad.nodo(nodo);
        unidad.estado(EstadoUnidad.AVERIADA);
        unidad.minutoDisponibleDesde(tipo.minutoReincorporacion(minutoActual));

        estado.anotarAveria(tipo);
        long minuto = minutoActual;
        String codigo = unidad.codigo();
        int x = Ciudad.x(nodo);
        int y = Ciudad.y(nodo);
        notificar(o -> o.alAveriarse(minuto, codigo, tipo.codigo(), x, y, cargaABordo));

        cola.programar(tipo.minutoReincorporacion(minutoActual), TipoEvento.FIN_AVERIA, indiceUnidad, tipo.codigo());
        if (tipo.trasladaAlmacenCentral()) {
            cola.programar(minutoActual + tipo.minutosEnElLugar(), TipoEvento.TRASLADO_A_CENTRAL, indiceUnidad, 0);
            if (cargaABordo > 0) {
                intentarTrasvase(u, tipo);
            }
        }
    }

    /** Indica si la unidad esta trabajando, es decir en ruta o detenida en una parada. */
    private boolean enOperacion(UnidadEnCurso u) {
        EstadoUnidad e = u.unidad().estado();
        return e == EstadoUnidad.EN_RUTA || e == EstadoUnidad.ENTREGANDO
                || e == EstadoUnidad.ABASTECIENDO || e == EstadoUnidad.EN_ALIMENTACION;
    }

    /**
     * La unidad averiada y los paquetes que no se trasvasaron se llevan de manera instantanea
     * al almacen central una vez cumplida la permanencia en el lugar del incidente.
     */
    private void trasladarAlCentral(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        if (u.averia() == null) {
            return;
        }
        u.limpiarItinerario(estado.central().nodo());
        u.unidad().nodo(estado.central().nodo());
        u.unidad().cargaABordo(0);
    }

    /** La unidad averiada vuelve a estar disponible para la planificacion. */
    private void reincorporar(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        if (u.averia() == null || u.unidad().estado() != EstadoUnidad.AVERIADA) {
            return;
        }
        u.averia(null);
        u.unidad().estado(EstadoUnidad.DISPONIBLE);
        u.unidad().minutoDisponibleDesde(minutoActual);
        u.limpiarItinerario(u.unidad().nodo());
    }

    // ------------------------------------------------------------- trasvase

    /**
     * Busca una unidad que pueda recoger la carga de la averiada antes de que se la lleven al
     * almacen central, y la despacha hacia el punto del incidente.
     *
     * <p>El trasvase de carga entre unidades toma {@code parametros.minutosTrasvase()}, es
     * decir 30 minutos por la respuesta 13 del cuestionario, de modo que solo se intenta con
     * unidades libres que alcancen el lugar y completen la operacion <b>antes</b> de que se
     * cumpla la permanencia en el sitio. La distancia se mide con una busqueda en anchura
     * sobre la reticula con la mascara de bloqueos vigente, que es el mismo recorrido con que
     * el planificador construye su matriz.</p>
     */
    private void intentarTrasvase(UnidadEnCurso averiada, TipoAveria tipo) {
        int nodoAveria = averiada.unidad().nodo();
        oraculo.distanciasDesde(nodoAveria, estado.bloqueos().mascaraBloqueada(minutoActual),
                distanciasAuxiliares, predecesoresAuxiliares);
        long limite = minutoActual + tipo.minutosEnElLugar();

        UnidadEnCurso elegida = null;
        int mejorKilometros = Integer.MAX_VALUE;
        long mejorLlegada = 0L;
        for (UnidadEnCurso candidata : estado.unidades()) {
            if (candidata == averiada || candidata.averia() != null || candidata.misionTrasvase()) {
                continue;
            }
            if (candidata.unidad().estado() != EstadoUnidad.DISPONIBLE
                    || candidata.unidad().capacidadLibre() <= 0) {
                continue;
            }
            int kilometros = distanciasAuxiliares[candidata.unidad().nodo()];
            if (kilometros >= MatrizDistancias.INALCANZABLE || kilometros >= mejorKilometros) {
                continue;
            }
            long llegada = minutoActual + parametrosVigentes.minutosDeViaje(candidata.unidad().tipo(), kilometros);
            if (llegada + parametrosVigentes.minutosTrasvase() >= limite) {
                continue;
            }
            elegida = candidata;
            mejorKilometros = kilometros;
            mejorLlegada = llegada;
        }
        if (elegida == null) {
            return;
        }

        int[] ida = invertir(oraculo.caminoDesdePredecesores(predecesoresAuxiliares,
                nodoAveria, elegida.unidad().nodo()));
        if (ida.length == 0) {
            return;
        }
        cobrarKilometros(elegida, elegida.indiceNodoActual(minutoActual));
        elegida.invalidarMovimiento();
        elegida.asignarItinerario(null, ida, new int[] {ida.length - 1},
                new long[] {mejorLlegada}, new long[] {mejorLlegada + parametrosVigentes.minutosTrasvase()},
                minutoActual);
        elegida.marcarMisionTrasvase(averiada.indice());
        elegida.unidad().estado(EstadoUnidad.EN_RUTA);
        elegida.unidad().minutoDisponibleDesde(mejorLlegada + parametrosVigentes.minutosTrasvase());
        long secuencia = cola.programar(mejorLlegada, TipoEvento.LLEGADA_A_PARADA,
                elegida.indice(), PARADA_TRASVASE).secuencia();
        elegida.secuenciaMovimiento(secuencia);
    }

    /** Cierra el trasvase: la carga que quepa pasa de la unidad averiada a la que la asiste. */
    private void completarTrasvase(UnidadEnCurso rescate) {
        UnidadEnCurso averiada = estado.unidad(rescate.unidadAsistida());
        int trasvasado = Math.min(rescate.unidad().capacidadLibre(), averiada.unidad().cargaABordo());
        if (trasvasado > 0) {
            averiada.unidad().cargaABordo(averiada.unidad().cargaABordo() - trasvasado);
            rescate.unidad().cargaABordo(rescate.unidad().cargaABordo() + trasvasado);
        }
        int nodo = rescate.unidad().nodo();
        rescate.limpiarItinerario(nodo);
        rescate.unidad().estado(EstadoUnidad.DISPONIBLE);
        rescate.unidad().minutoDisponibleDesde(minutoActual);
    }

    // ------------------------------------------------------- ejecucion de rutas

    /** Una unidad alcanza la siguiente parada de su itinerario. */
    private void llegarAParada(Evento evento) {
        UnidadEnCurso u = estado.unidad(evento.referencia());
        if (!u.vigente(evento)) {
            return;
        }
        int k = evento.dato();
        int indiceNodo = u.desplazamientoParada(k);
        cobrarKilometros(u, indiceNodo);
        u.unidad().nodo(u.nodoEn(indiceNodo));
        u.llegarA(k);

        if (u.misionTrasvase()) {
            u.unidad().estado(EstadoUnidad.ABASTECIENDO);
            programarMovimiento(u, u.minutoSalidaParada(k), TipoEvento.FIN_SERVICIO, k);
            return;
        }

        Parada parada = u.ruta().paradas().get(k);
        switch (parada.tipo()) {
            case ENTREGA -> {
                entregar(u, parada);
                u.unidad().estado(EstadoUnidad.ENTREGANDO);
            }
            case ABASTECIMIENTO -> {
                abastecer(u, parada);
                u.unidad().estado(EstadoUnidad.ABASTECIENDO);
            }
            case ALIMENTACION -> u.unidad().estado(EstadoUnidad.EN_ALIMENTACION);
            default -> throw new IllegalStateException("Tipo de parada no contemplado: " + parada.tipo());
        }
        programarMovimiento(u, u.minutoSalidaParada(k), TipoEvento.FIN_SERVICIO, k);
    }

    /**
     * Descarga la parte del pedido que corresponde a esta parada. Si el pedido queda completo
     * se publica el aviso con los minutos transcurridos desde su registro, que es lo que
     * alimenta el tiempo de entrega promedio por prioridad del panel de metricas.
     */
    private void entregar(UnidadEnCurso u, Parada parada) {
        int indicePedido = estado.indiceDePedido(parada.idPedido());
        if (indicePedido < 0) {
            return;
        }
        int cantidad = Math.min(Math.min(parada.cantidad(), estado.pendienteDe(indicePedido)),
                u.unidad().cargaABordo());
        if (cantidad <= 0) {
            return;
        }
        u.unidad().cargaABordo(u.unidad().cargaABordo() - cantidad);
        if (!estado.entregar(indicePedido, cantidad, minutoActual)) {
            return;
        }
        Pedido pedido = estado.pedido(indicePedido);
        long minuto = minutoActual;
        String codigo = u.unidad().codigo();
        long desdeRegistro = minuto - pedido.minutoRegistro();
        notificar(o -> o.alEntregarPedido(minuto, pedido.id(), codigo, pedido.cantidad(),
                desdeRegistro, pedido.plazoHoras()));
    }

    /** Carga producto P en un almacen y revisa si con ello cambio su color de semaforo. */
    private void abastecer(UnidadEnCurso u, Parada parada) {
        int indiceAlmacen = estado.indiceDeAlmacen(parada.idAlmacen());
        if (indiceAlmacen < 0) {
            return;
        }
        int solicitado = Math.min(parada.cantidad(), u.unidad().capacidadLibre());
        int retirado = estado.consumirInventario(indiceAlmacen, solicitado);
        if (retirado > 0) {
            u.unidad().cargaABordo(u.unidad().cargaABordo() + retirado);
        }
        revisarSemaforo(indiceAlmacen);
    }

    /** Una unidad termina el servicio de una parada y continua su itinerario. */
    private void terminarServicio(Evento evento) {
        UnidadEnCurso u = estado.unidad(evento.referencia());
        if (!u.vigente(evento)) {
            return;
        }
        if (u.indiceParada() == UnidadEnCurso.SERVICIO_HEREDADO) {
            arrancarItinerario(u);
            return;
        }
        if (u.misionTrasvase()) {
            completarTrasvase(u);
            return;
        }
        int siguiente = evento.dato() + 1;
        if (siguiente < u.cantidadParadas()) {
            u.partirHacia(siguiente, minutoActual);
            u.unidad().estado(EstadoUnidad.EN_RUTA);
            programarMovimiento(u, u.minutoLlegadaParada(siguiente), TipoEvento.LLEGADA_A_PARADA, siguiente);
        } else {
            u.terminarItinerario();
            u.unidad().estado(EstadoUnidad.DISPONIBLE);
            u.unidad().minutoDisponibleDesde(minutoActual);
        }
    }

    /** Arranca el itinerario comprometido durante un servicio que la unidad estaba atendiendo. */
    private void arrancarItinerario(UnidadEnCurso u) {
        if (u.cantidadParadas() == 0) {
            u.terminarItinerario();
            u.unidad().estado(EstadoUnidad.DISPONIBLE);
            u.unidad().minutoDisponibleDesde(minutoActual);
            return;
        }
        u.partirHacia(0, minutoActual);
        u.unidad().estado(EstadoUnidad.EN_RUTA);
        programarMovimiento(u, u.minutoLlegadaParada(0), TipoEvento.LLEGADA_A_PARADA, 0);
    }

    /** Encola el proximo movimiento de la unidad y lo declara su unico evento vigente. */
    private void programarMovimiento(UnidadEnCurso u, long minuto, TipoEvento tipo, int parada) {
        long secuencia = cola.programar(Math.max(minutoActual, minuto), tipo, u.indice(), parada).secuencia();
        u.secuenciaMovimiento(secuencia);
    }

    /** Contabiliza los kilometros y el costo de operacion recorridos hasta el nodo indicado. */
    private void cobrarKilometros(UnidadEnCurso u, int indiceEnCamino) {
        estado.anotarRecorrido(u.unidad().tipo(), u.cobrarHasta(indiceEnCamino));
    }

    // ------------------------------------------------------- replanificacion

    /**
     * Ejecuta una iteracion completa del planificador y compromete el plan resultante.
     *
     * <p>El candado del motor se suelta mientras el algoritmo trabaja, que es el tramo largo
     * de la iteracion, de modo que el visualizador pueda seguir pidiendo fotografias. El
     * algoritmo opera sobre una {@link InstanciaPlanificacion} inmutable, de modo que no ve
     * el estado del mundo cambiar bajo sus pies.</p>
     *
     * <p>Cada replanificacion recibe su propia semilla, derivada con
     * {@link Aleatorio#derivarSemilla(long, long)} de la semilla de la configuracion y del
     * numero de orden de la replanificacion dentro de la corrida, contado desde cero. Con la
     * semilla fija del algoritmo todas las iteraciones repetian la misma secuencia aleatoria
     * sobre fotografias muy parecidas, de modo que los mismos sesgos de la busqueda se
     * repetian una y otra vez; con una semilla por iteracion cada una explora por su cuenta y
     * la corrida entera sigue siendo reproducible a partir de la semilla de la configuracion,
     * como pide el apartado 10 del ISA. La derivacion mezcla con SplitMix64 y no se limita a
     * sumar el numero de orden, porque semillas que avanzan por un paso fijo darian corrientes
     * solapadas en el generador.</p>
     *
     * <p>Con {@code ConfiguracionEscenario.arranqueDesdePlanVigente} activo y un algoritmo que
     * lo admite, la iteracion no arranca desde la heuristica constructiva sino desde el plan
     * vigente adaptado a la fotografia, conforme al apartado 7.3.5 del ISA. El plan se
     * construye solo en ese caso, y la primera replanificacion de la corrida arranca de todos
     * modos con la constructiva, porque todavia no hay nada que heredar.</p>
     */
    private void replanificar() {
        ParametrosOperacion.Instantanea foto = parametros.instantanea();
        boolean desdePlanVigente = configuracion.arranqueDesdePlanVigente()
                && algoritmo.admiteArranqueDesdePlanVigente();
        Fotografia fotografia;
        candado.lock();
        try {
            parametrosVigentes = foto;
            fotografia = construirInstancia(foto, desdePlanVigente);
        } finally {
            candado.unlock();
        }

        PresupuestoComputo presupuesto = PresupuestoComputo
                .deSimulacion(configuracion.saltoMinutos(), configuracion.factorAceleracion())
                .arrancar();
        presupuestoVigente = presupuesto;
        if (cancelado) {
            presupuesto.cancelar();
        }
        long semillaIteracion = Aleatorio.derivarSemilla(configuracion.semilla(), replanificacionesLanzadas++);
        ResultadoPlanificacion plan = fotografia.planVigente() == null
                ? algoritmo.resolver(fotografia.instancia(), presupuesto, semillaIteracion)
                : algoritmo.resolverDesde(fotografia.instancia(), presupuesto, semillaIteracion,
                        fotografia.planVigente());
        presupuestoVigente = null;

        candado.lock();
        try {
            comprometer(fotografia, plan.solucion());
            estado.anotarEjecucionPlanificador(plan.milisegundos());
        } finally {
            candado.unlock();
        }

        long minuto = minutoActual;
        int pendientes = fotografia.instancia().cantidadPedidos();
        int noAtendidos = plan.solucion().h();
        double costo = plan.solucion().costo();
        long milisegundos = plan.milisegundos();
        String nombre = plan.algoritmo();
        notificar(o -> o.alReplanificar(minuto, nombre, pendientes, noAtendidos, costo, milisegundos));
    }

    /**
     * Fotografia de planificacion y correspondencia entre los indices de la instancia y las
     * unidades vivas del motor.
     *
     * @param instancia        fotografia estatica que consume el algoritmo
     * @param unidades         unidades planificadas, en el orden en que entraron a la instancia
     * @param nodoRedireccion  nodo desde el que se planifico cada una
     * @param planVigente      plan de partida del apartado 7.3.5, ya recortado a la
     *                         fotografia, o {@code null} si esta iteracion no arranca desde el
     *                         plan vigente
     */
    private record Fotografia(InstanciaPlanificacion instancia, List<UnidadEnCurso> unidades,
                              int[] nodoRedireccion, Solucion planVigente) {
    }

    /**
     * Construye la fotografia estatica del problema en el instante actual y, cuando se pide,
     * el plan vigente adaptado a ella.
     *
     * @param conPlanVigente si ademas hay que construir la solucion de partida del apartado
     *                       7.3.5 del ISA
     */
    private Fotografia construirInstancia(ParametrosOperacion.Instantanea foto, boolean conPlanVigente) {
        long minuto = minutoActual;
        int[] pendientes = estado.pendientesEn(minuto);
        List<Almacen> almacenes = estado.almacenes();

        List<UnidadEnCurso> planificables = new ArrayList<>(estado.unidades().length);
        for (UnidadEnCurso u : estado.unidades()) {
            // Todo vehiculo, en almacen o en ruta, se replanifica en cada iteracion
            // (apartado 2.2 del ISA). Quedan fuera solo los que no pueden recibir ruta: los
            // averiados, los que estan en mantenimiento preventivo y los comprometidos en un
            // trasvase que aun no termina.
            if (u.averia() != null || u.unidad().estado() == EstadoUnidad.EN_MANTENIMIENTO
                    || u.misionTrasvase()) {
                continue;
            }
            planificables.add(u);
        }

        int[] nodoRedireccion = new int[planificables.size()];
        long[] minutoDisponible = new long[planificables.size()];
        for (int i = 0; i < planificables.size(); i++) {
            UnidadEnCurso u = planificables.get(i);
            if (u.sirviendo()) {
                // Ni la hora de acondicionamiento ni la pausa de alimentacion se interrumpen.
                nodoRedireccion[i] = u.nodoActual(minuto);
                minutoDisponible[i] = Math.max(minuto, u.minutoSalidaParadaEnCurso());
            } else if (u.indiceParada() >= 0) {
                // En marcha: no puede darse la vuelta en mitad de la calle, de modo que se
                // planifica desde el proximo nodo que alcanzara.
                int indice = u.indiceRedireccion(minuto);
                nodoRedireccion[i] = u.nodoEn(indice);
                minutoDisponible[i] = Math.max(minuto, u.minutoLlegadaANodo(indice));
            } else {
                nodoRedireccion[i] = u.unidad().nodo();
                minutoDisponible[i] = Math.max(minuto, u.unidad().minutoDisponibleDesde());
            }
        }

        int[] nodosPorPunto = new int[almacenes.size() + pendientes.length + planificables.size()];
        int k = 0;
        for (Almacen a : almacenes) {
            nodosPorPunto[k++] = a.nodo();
        }
        for (int indicePedido : pendientes) {
            nodosPorPunto[k++] = estado.pedido(indicePedido).nodoDestino();
        }
        for (int nodo : nodoRedireccion) {
            nodosPorPunto[k++] = nodo;
        }
        MatrizDistanciasReticula matriz = MatrizDistanciasReticula.construir(
                nodosPorPunto, estado.bloqueos().mascaraBloqueada(minuto), oraculo);

        InstanciaPlanificacion.Constructor constructor = InstanciaPlanificacion.constructor()
                .minutoActual(minuto)
                .parametros(foto)
                .matriz(matriz);
        for (int i = 0; i < almacenes.size(); i++) {
            constructor.almacen(almacenes.get(i), estado.inventario(i));
        }
        for (int indicePedido : pendientes) {
            constructor.pedido(estado.pedido(indicePedido), estado.pendienteDe(indicePedido));
        }
        for (int i = 0; i < planificables.size(); i++) {
            UnidadEnCurso u = planificables.get(i);
            // La instancia recibe un espejo y no la unidad viva: su nodo es el de
            // redireccion, que todavia no ha alcanzado, y escribirlo en la unidad viva
            // adelantaria su posicion en el visualizador.
            UnidadTransporte espejo = new UnidadTransporte(u.codigo(), u.unidad().tipo(), nodoRedireccion[i]);
            espejo.cargaABordo(u.unidad().cargaABordo());
            espejo.minutoDisponibleDesde(minutoDisponible[i]);
            constructor.unidad(espejo);
        }
        for (UnidadEnCurso u : planificables) {
            for (Parada p : paradasPendientes(u)) {
                if (p.tipo() == TipoParada.ENTREGA) {
                    constructor.asignacionVigente(p.idPedido(), u.codigo());
                }
            }
        }
        InstanciaPlanificacion instancia = constructor.construir();
        Solucion plan = conPlanVigente ? planVigenteAdaptado(instancia, planificables, nodoRedireccion) : null;
        return new Fotografia(instancia, planificables, nodoRedireccion, plan);
    }

    /**
     * Plan vigente recortado a la fotografia, que es la solucion de partida del segundo modo
     * de arranque del apartado 7.3.5 del ISA.
     *
     * <p>Se conserva la asignacion de pedido a unidad de las paradas de entrega que cada
     * unidad planificada todavia no ha atendido, y solo eso: quedan fuera las unidades que ya
     * no estan disponibles, porque no entran en la lista de planificables, y los pedidos que
     * ya no estan pendientes, porque no entran en la instancia. La cantidad de cada entrega se
     * recorta a lo que del pedido sigue pendiente, de modo que el plan nunca declare entregar
     * mas de lo que queda. No se copian ni los abastecimientos ni la pausa de alimentacion, que
     * el decodificador vuelve a decidir, y los instantes y kilometros que traen las paradas son
     * los que previo el plan anterior: es una semilla, no un plan programado, y el algoritmo la
     * reevalua ruta a ruta al cargarla, recortando por la cola lo que ya no sea factible.</p>
     *
     * @return el plan, o {@code null} si no queda ninguna entrega que heredar, que es el caso
     *         de la primera replanificacion de la corrida
     */
    private Solucion planVigenteAdaptado(InstanciaPlanificacion instancia, List<UnidadEnCurso> planificables,
                                         int[] nodoRedireccion) {
        int[] asignado = new int[instancia.cantidadPedidos()];
        List<Ruta> rutas = new ArrayList<>(planificables.size());
        for (int i = 0; i < planificables.size(); i++) {
            UnidadEnCurso u = planificables.get(i);
            List<Parada> entregas = new ArrayList<>();
            for (Parada p : paradasPendientes(u)) {
                if (p.tipo() != TipoParada.ENTREGA) {
                    continue;
                }
                int pedido = instancia.indiceDePedido(p.idPedido());
                if (pedido < 0) {
                    continue;
                }
                int cantidad = Math.min(p.cantidad(), instancia.pedidoCantidad(pedido) - asignado[pedido]);
                if (cantidad <= 0) {
                    continue;
                }
                asignado[pedido] += cantidad;
                entregas.add(cantidad == p.cantidad() ? p
                        : new Parada(TipoParada.ENTREGA, p.nodo(), p.idPedido(), -1, cantidad,
                                p.minutoLlegada(), p.minutoSalida(), p.kmDesdeAnterior()));
            }
            if (!entregas.isEmpty()) {
                rutas.add(new Ruta(u.codigo(), u.unidad().tipo(), nodoRedireccion[i],
                        instancia.unidadMinutoDisponible(i), entregas));
            }
        }
        if (rutas.isEmpty()) {
            return null;
        }
        Map<Integer, Integer> banco = new LinkedHashMap<>();
        for (int p = 0; p < instancia.cantidadPedidos(); p++) {
            int falta = instancia.pedidoCantidad(p) - asignado[p];
            if (falta > 0) {
                banco.put(instancia.pedidoId(p), falta);
            }
        }
        // El valor no se calcula: la solucion es una semilla y quien la recibe la reevalua.
        return new Solucion(rutas, banco, ValorObjetivo.PEOR);
    }

    /** Paradas del itinerario vigente que la unidad todavia no ha atendido. */
    private List<Parada> paradasPendientes(UnidadEnCurso u) {
        Ruta ruta = u.ruta();
        if (ruta == null || u.indiceParada() == UnidadEnCurso.SIN_PARADA) {
            return List.of();
        }
        int desde = u.indiceParada() < 0 ? 0 : (u.sirviendo() ? u.indiceParada() + 1 : u.indiceParada());
        List<Parada> paradas = ruta.paradas();
        return desde >= paradas.size() ? List.of() : paradas.subList(desde, paradas.size());
    }

    /** Entrega a cada unidad planificada la ruta que le asigno el plan nuevo. */
    private void comprometer(Fotografia fotografia, Solucion solucion) {
        Map<String, Ruta> porUnidad = new HashMap<>(solucion.rutas().size() * 2);
        for (Ruta r : solucion.rutas()) {
            porUnidad.put(r.codigoUnidad(), r);
        }
        for (int i = 0; i < fotografia.unidades().size(); i++) {
            UnidadEnCurso u = fotografia.unidades().get(i);
            comprometerUnidad(u, i, fotografia, porUnidad.get(u.codigo()));
        }
    }

    /**
     * Compromete la ruta de una unidad concreta.
     *
     * <p>El camino nodo a nodo se toma de {@code matriz.camino(a,b)}, es decir del mismo
     * objeto con que el planificador costeo la ruta. Arranca en el nodo fisico de la unidad y,
     * si esa posicion no coincide con el nodo desde el que se planifico, incorpora como
     * cabecera el kilometro que le falta por recorrer.</p>
     */
    private void comprometerUnidad(UnidadEnCurso u, int indiceEnInstancia, Fotografia fotografia, Ruta ruta) {
        long minuto = minutoActual;
        int indiceNodo = u.indiceNodoActual(minuto);
        int nodoFisico = u.nodoEn(indiceNodo);
        cobrarKilometros(u, indiceNodo);
        long finServicio = u.sirviendo() ? u.minutoSalidaParadaEnCurso() : Long.MIN_VALUE;
        u.invalidarMovimiento();

        if (ruta == null || ruta.paradas().isEmpty()) {
            // Sin trabajo asignado la unidad se detiene donde esta. El nodo de redireccion
            // deja de tener sentido: la iteracion siguiente la leera en su posicion real.
            u.limpiarItinerario(nodoFisico);
            u.unidad().nodo(nodoFisico);
            if (finServicio > minuto) {
                u.marcarServicioHeredado(finServicio);
                programarMovimiento(u, finServicio, TipoEvento.FIN_SERVICIO, UnidadEnCurso.SERVICIO_HEREDADO);
            } else {
                u.unidad().estado(EstadoUnidad.DISPONIBLE);
                u.unidad().minutoDisponibleDesde(minuto);
            }
            return;
        }

        InstanciaPlanificacion instancia = fotografia.instancia();
        int nodoRedireccion = fotografia.nodoRedireccion()[indiceEnInstancia];
        reiniciarTrabajo(nodoFisico);
        if (nodoRedireccion != nodoFisico) {
            agregarNodo(nodoRedireccion);
        }

        List<Parada> paradas = ruta.paradas();
        int[] desplazamiento = new int[paradas.size()];
        long[] llegadas = new long[paradas.size()];
        long[] salidas = new long[paradas.size()];
        int puntoAnterior = instancia.puntoUnidad(indiceEnInstancia);
        for (int k = 0; k < paradas.size(); k++) {
            Parada parada = paradas.get(k);
            int punto = puntoDeParada(instancia, parada);
            if (punto >= 0 && punto != puntoAnterior) {
                agregarCamino(instancia.matriz(), puntoAnterior, punto);
                puntoAnterior = punto;
            }
            desplazamiento[k] = longitudTrabajo - 1;
            llegadas[k] = Math.max(minuto, parada.minutoLlegada());
            salidas[k] = Math.max(llegadas[k], parada.minutoSalida());
        }

        long salidaTramo = nodoRedireccion != nodoFisico ? minuto : Math.max(minuto, ruta.minutoInicio());
        u.asignarItinerario(ruta, Arrays.copyOf(caminoTrabajo, longitudTrabajo),
                desplazamiento, llegadas, salidas, salidaTramo);
        u.unidad().nodo(nodoFisico);
        if (finServicio > minuto) {
            u.marcarServicioHeredado(finServicio);
            programarMovimiento(u, finServicio, TipoEvento.FIN_SERVICIO, UnidadEnCurso.SERVICIO_HEREDADO);
        } else {
            u.unidad().estado(EstadoUnidad.EN_RUTA);
            programarMovimiento(u, llegadas[0], TipoEvento.LLEGADA_A_PARADA, 0);
        }
    }

    /** Punto de la matriz que corresponde a una parada, o {@code -1} si no supone desplazamiento. */
    private int puntoDeParada(InstanciaPlanificacion instancia, Parada parada) {
        return switch (parada.tipo()) {
            case ENTREGA -> {
                int i = instancia.indiceDePedido(parada.idPedido());
                yield i < 0 ? -1 : instancia.puntoPedido(i);
            }
            case ABASTECIMIENTO -> {
                int i = indiceDeAlmacenEnInstancia(instancia, parada.idAlmacen());
                yield i < 0 ? -1 : instancia.puntoAlmacen(i);
            }
            // La pausa de alimentacion ocurre donde la unidad se encuentra: no mueve nada.
            case ALIMENTACION -> -1;
        };
    }

    /** Posicion del almacen dentro de la instancia a partir de su identificador. */
    private static int indiceDeAlmacenEnInstancia(InstanciaPlanificacion instancia, int idAlmacen) {
        for (int i = 0; i < instancia.cantidadAlmacenes(); i++) {
            if (instancia.almacenId(i) == idAlmacen) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------ camino de trabajo

    /** Reinicia el camino en construccion con el nodo dado. */
    private void reiniciarTrabajo(int nodo) {
        longitudTrabajo = 0;
        agregarNodo(nodo);
    }

    /** Anade un nodo al camino en construccion. */
    private void agregarNodo(int nodo) {
        if (longitudTrabajo == caminoTrabajo.length) {
            caminoTrabajo = Arrays.copyOf(caminoTrabajo, longitudTrabajo * 2);
        }
        caminoTrabajo[longitudTrabajo++] = nodo;
    }

    /**
     * Anade al camino en construccion el trayecto entre dos puntos de la matriz, sin repetir
     * el nodo de arranque. Si la matriz no devuelve camino, cosa que con las poligonales
     * abiertas del curso no llega a ocurrir, se recurre a un trayecto en L sobre la reticula
     * para no perder ni el trazo ni el kilometraje.
     */
    private void agregarCamino(MatrizDistancias matriz, int puntoOrigen, int puntoDestino) {
        int[] tramo = matriz.camino(puntoOrigen, puntoDestino);
        if (tramo.length == 0) {
            agregarCaminoEnL(matriz.nodo(puntoOrigen), matriz.nodo(puntoDestino));
            return;
        }
        for (int i = 1; i < tramo.length; i++) {
            agregarNodo(tramo[i]);
        }
    }

    /** Trayecto de reserva sobre la reticula: primero el eje X y luego el eje Y. */
    private void agregarCaminoEnL(int nodoOrigen, int nodoDestino) {
        int x = Ciudad.x(nodoOrigen);
        int y = Ciudad.y(nodoOrigen);
        int destinoX = Ciudad.x(nodoDestino);
        int destinoY = Ciudad.y(nodoDestino);
        while (x != destinoX) {
            x += x < destinoX ? 1 : -1;
            agregarNodo(Ciudad.nodo(x, y));
        }
        while (y != destinoY) {
            y += y < destinoY ? 1 : -1;
            agregarNodo(Ciudad.nodo(x, y));
        }
    }

    /** Invierte el orden de un camino. */
    private static int[] invertir(int[] camino) {
        int[] inverso = new int[camino.length];
        for (int i = 0; i < camino.length; i++) {
            inverso[i] = camino[camino.length - 1 - i];
        }
        return inverso;
    }

    // ------------------------------------------------------------ fotografias

    /** Toma una fotografia del estado y la publica a los observadores. */
    private void fotografiar() {
        InstantaneaSimulacion instantanea;
        candado.lock();
        try {
            instantanea = construirInstantanea();
        } finally {
            candado.unlock();
        }
        notificar(o -> o.alTomarFotografia(instantanea));
    }

    /** Construye la fotografia del estado. Debe invocarse con el candado tomado. */
    private InstantaneaSimulacion construirInstantanea() {
        long minuto = minutoActual;
        UnidadEnCurso[] unidades = estado.unidades();
        List<VistaUnidad> vistas = new ArrayList<>(unidades.length);
        for (UnidadEnCurso u : unidades) {
            vistas.add(estado.vistaDe(u, minuto));
        }
        return new InstantaneaSimulacion(minuto, calendario.aFecha(minuto), estadoCorrida,
                milisegundosReales(), vistas, estado.vistasDeAlmacenes(), estado.vistasDeBloqueos(minuto),
                estado.pedidosPendientes(), estado.metricas());
    }

    /**
     * Publica un aviso a todos los observadores. Una excepcion de un observador no puede
     * detener la operacion: el motor la absorbe y sigue con el resto.
     */
    private void notificar(Consumer<ObservadorSimulacion> aviso) {
        for (ObservadorSimulacion observador : observadores) {
            try {
                aviso.accept(observador);
            } catch (RuntimeException ignorado) {
                // Un consumidor caido no interrumpe la simulacion.
            }
        }
    }

    /** Tipos de unidad de la flota del escenario, para los reportes del modulo de servicio. */
    public List<TipoUnidad> tiposDeUnidad() {
        return List.of(TipoUnidad.values());
    }
}
