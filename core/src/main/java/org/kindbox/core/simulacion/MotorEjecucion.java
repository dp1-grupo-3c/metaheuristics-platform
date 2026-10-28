package org.kindbox.core.simulacion;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

public class MotorEjecucion {

    public static final int SIN_UMBRAL_INCUMPLIMIENTOS = Integer.MAX_VALUE;

    private static final int PARADA_TRASVASE = 0;

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
    private final boolean operacion;
    private List<Mantenimiento> mantenimientos;
    private ExecutorService buscador;
    private Future<ResultadoPlanificacion> busqueda;
    private Fotografia fotografiaEnCalculo;
    private long revisionMundo;
    private long revisionEnCalculo;
    private long minutoEnCalculo;
    private long presupuestoOperacionMs;
    private boolean otraPlanificacion;
    private long planesDescartados;

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
    private final Map<Long, LinkedHashSet<String>> motivosReplanificacion = new HashMap<>();

    private long replanificacionesLanzadas;

    private int[] caminoTrabajo = new int[512];
    private int longitudTrabajo;

    public MotorEjecucion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                           ParametrosOperacion parametros, Algoritmo algoritmo,
                           List<ObservadorSimulacion> observadores) {
        this(datos, configuracion, parametros, algoritmo, observadores, false);
    }

    public MotorEjecucion(RepositorioDatos.DatosEscenario datos, ConfiguracionEscenario configuracion,
                          ParametrosOperacion parametros, Algoritmo algoritmo,
                          List<ObservadorSimulacion> observadores, boolean operacion) {
        this.operacion = operacion;
        if (datos == null || configuracion == null || parametros == null || algoritmo == null) {
            throw new IllegalArgumentException("El motor necesita datos, configuracion, parametros y algoritmo");
        }
        this.datos = datos;
        this.configuracion = configuracion;
        this.parametros = parametros;
        this.algoritmo = algoritmo;
        this.observadores = new CopyOnWriteArrayList<>(observadores == null ? List.of() : observadores);
        this.calendario = datos.calendario();
        this.mantenimientos = datos.mantenimientos();
        this.generadorAverias = GeneradorAverias.deConfiguracion(configuracion);
        this.parametrosVigentes = parametros.instantanea();

        RegistroBloqueos bloqueos = new RegistroBloqueos(datos.bloqueos());
        this.estado = new EstadoSimulacion(calendario, bloqueos, Almacen.todos(), datos.pedidos(), datos.unidades());

        long cierreConfigurado = calendario.aMinutos(configuracion.ultimoDia().plusDays(1).atStartOfDay());
        this.minutoFin = operacion ? Long.MAX_VALUE : Math.min(cierreConfigurado, datos.minutoFin());
        if (minutoFin <= 0) {
            throw new IllegalArgumentException("El horizonte del escenario es vacio: el ultimo dia configurado ("
                    + configuracion.ultimoDia() + ") no alcanza el primer dia de los datos (" + datos.primerDia() + ")");
        }
        if (!operacion) sembrar();
    }

    public void iniciarOperacion(long minuto, ExecutorService ejecutor, long presupuestoMs) {
        if (!operacion || minuto < 0 || !arrancada.compareAndSet(false, true)) {
            throw new IllegalStateException("La operacion no puede iniciarse");
        }
        buscador = java.util.Objects.requireNonNull(ejecutor);
        presupuestoOperacionMs = presupuestoMs;
        minutoActual = minuto;
        nanoInicio = System.nanoTime();
        estadoCorrida = EstadoCorrida.EN_CURSO;
    }

    public void actualizarEntorno(List<Bloqueo> bloqueos, List<Mantenimiento> nuevos) {
        exigirOperacion();
        estado.actualizarBloqueos(new RegistroBloqueos(bloqueos));
        mantenimientos = List.copyOf(nuevos);
        revisionMundo++;
    }

    public void agendarDia(long base, List<Bloqueo> bloqueos) {
        exigirOperacion();
        long fin = base + CalendarioEscenario.MINUTOS_POR_DIA;
        for (Bloqueo bloqueo : bloqueos) {
            if (bloqueo.minutoInicio() >= Math.max(base, minutoActual) && bloqueo.minutoInicio() < fin) {
                cola.programar(bloqueo.minutoInicio(), TipoEvento.ACTIVACION_BLOQUEO);
            }
            if (bloqueo.minutoFin() >= Math.max(base, minutoActual) && bloqueo.minutoFin() < fin) {
                cola.programar(bloqueo.minutoFin(), TipoEvento.DESACTIVACION_BLOQUEO);
            }
        }
        for (Mantenimiento mantenimiento : mantenimientos) {
            if (calendario.aMinutos(mantenimiento.fecha()) != base) continue;
            int unidad = estado.indiceDeUnidad(mantenimiento.codigoUnidad());
            if (unidad < 0) continue;
            if (base < minutoActual && minutoActual < fin) {
                iniciarMantenimiento(unidad);
                estado.unidad(unidad).unidad().minutoDisponibleDesde(fin);
            } else if (base >= minutoActual) {
                cola.programar(base, TipoEvento.INICIO_MANTENIMIENTO, unidad, 0);
            }
            if (fin > minutoActual) cola.programar(fin, TipoEvento.FIN_MANTENIMIENTO, unidad, 0);
        }
        long recarga = base + Almacen.MINUTO_RECARGA_DIARIA;
        if (recarga >= minutoActual) cola.programar(recarga, TipoEvento.RECARGA_ALMACENES);
        for (int cambio : Turno.CAMBIOS_DE_TURNO) {
            if (base + cambio >= minutoActual) cola.programar(base + cambio, TipoEvento.CAMBIO_TURNO);
        }
        for (long minuto = base; minuto < fin; minuto += configuracion.saltoMinutos()) {
            if (minuto >= minutoActual) cola.programar(minuto, TipoEvento.REPLANIFICACION);
        }
    }

    public void incorporarPedido(Pedido pedido) {
        exigirOperacion();
        if (pedido.minutoRegistro() != minutoActual) {
            throw new IllegalArgumentException("La fecha del pedido debe coincidir con su aplicacion");
        }
        estado.incorporarPedido(pedido);
        solicitarReplanificacion("PEDIDO_NUEVO");
    }

    public void aplicarAveria(String codigo, TipoAveria tipo) {
        exigirOperacion();
        int indice = estado.indiceDeUnidad(codigo);
        if (tipo == null || indice < 0) throw new IllegalArgumentException("La unidad o el tipo no existe");
        UnidadEnCurso unidad = estado.unidad(indice);
        if (unidad.averia() != null || unidad.unidad().estado() == EstadoUnidad.EN_MANTENIMIENTO) {
            throw new IllegalStateException("La unidad ya esta deshabilitada");
        }
        revisionMundo++;
        averiar(indice, -tipo.codigo());
    }

    public void solicitarPlanOperacion() {
        exigirOperacion();
        revisionMundo++;
        solicitarReplanificacion("PARAMETROS");
    }

    public void avanzarOperacion(long objetivo) {
        exigirOperacion();
        if (objetivo < minutoActual) throw new IllegalArgumentException("El reloj no puede retroceder");
        while (true) {
            Evento proximo = cola.asomar();
            long vencimiento = vencimientoPendiente();
            long siguiente = Math.min(proximo == null ? Long.MAX_VALUE : proximo.minuto(), vencimiento);
            if (siguiente > objetivo) break;
            minutoActual = Math.max(minutoActual, siguiente);
            if (proximo != null && proximo.minuto() <= vencimiento) {
                Evento evento = cola.siguiente();
                if (evento.tipo() != TipoEvento.REPLANIFICACION && evento.tipo() != TipoEvento.FOTOGRAFIA) {
                    revisionMundo++;
                }
                procesar(evento);
            } else {
                revisionMundo++;
                resolverVencimientos();
            }
        }
        minutoActual = objetivo;
        recibirPlanOperacion();
    }

    public long planesDescartados() {
        return planesDescartados;
    }

    public boolean calculando() {
        return busqueda != null;
    }

    public void detenerOperacion() {
        exigirOperacion();
        cancelar();
        estadoCorrida = EstadoCorrida.CANCELADA;
    }

    public void fallarOperacion() {
        cancelar();
        estadoCorrida = EstadoCorrida.FALLIDA;
    }

    private void exigirOperacion() {
        if (!operacion || estadoCorrida != EstadoCorrida.EN_CURSO || cancelado) {
            throw new IllegalStateException("La operacion no esta en curso");
        }
    }

    private void recibirPlanOperacion() {
        if (busqueda == null || !busqueda.isDone()) return;
        ResultadoPlanificacion plan;
        try {
            plan = busqueda.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La planificacion fue interrumpida", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Fallo el planificador operativo", e.getCause());
        } finally {
            busqueda = null;
            presupuestoVigente = null;
        }
        Fotografia foto = fotografiaEnCalculo;
        boolean vigente = minutoActual == minutoEnCalculo && revisionMundo == revisionEnCalculo
                && parametros.instantanea().version() == foto.instancia().parametros().version();
        if (vigente) {
            var comprobacion = new org.kindbox.core.evaluacion.VerificadorRestricciones()
                    .verificar(foto.instancia(), plan.solucion());
            if (!comprobacion.factible()) throw new IllegalStateException("El plan operativo no es factible");
            comprometer(foto, plan.solucion());
            estado.anotarEjecucionPlanificador(plan.milisegundos());
            notificar(o -> o.alPublicarPlan(minutoActual, foto.instancia(), plan));
            notificar(o -> o.alReplanificar(minutoActual, plan.algoritmo(), foto.instancia().cantidadPedidos(),
                    plan.solucion().h(), plan.solucion().costo(), plan.milisegundos()));
        } else {
            planesDescartados++;
        }
        fotografiaEnCalculo = null;
        if (!vigente || otraPlanificacion) {
            otraPlanificacion = false;
            solicitarReplanificacion("ACTUALIZACION");
        }
    }

    public ConfiguracionEscenario configuracion() {
        return configuracion;
    }

    public ParametrosOperacion parametros() {
        return parametros;
    }

    public EstadoCorrida estadoCorrida() {
        return estadoCorrida;
    }

    public long minutoActual() {
        return minutoActual;
    }

    public long minutoFin() {
        return minutoFin;
    }

    public ResultadoSimulacion resultado() {
        return resultado;
    }

    public void umbralIncumplimientos(int incumplimientos) {
        if (incumplimientos <= 0) {
            throw new IllegalArgumentException("El umbral de incumplimientos debe ser positivo");
        }
        this.umbralIncumplimientos = incumplimientos;
    }

    public int umbralIncumplimientos() {
        return umbralIncumplimientos;
    }

    public void agregarObservador(ObservadorSimulacion observador) {
        if (observador != null) {
            observadores.add(observador);
        }
    }

    public void quitarObservador(ObservadorSimulacion observador) {
        observadores.remove(observador);
    }

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

    public boolean cancelado() {
        return cancelado;
    }

    public InstantaneaSimulacion instantanea() {
        candado.lock();
        try {
            return construirInstantanea();
        } finally {
            candado.unlock();
        }
    }

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

    public EstadoSimulacion estado() {
        return estado;
    }

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
            cola.programar(Math.min(minutoFin, inicio + CalendarioEscenario.MINUTOS_POR_DIA),
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
            motivosReplanificacion.computeIfAbsent(minuto, k -> new LinkedHashSet<>()).add("PERIODICA");
            cola.programar(minuto, TipoEvento.REPLANIFICACION);
        }
        for (long minuto = 0; minuto <= minutoFin; minuto += configuracion.minutosEntreFotografias()) {
            cola.programar(minuto, TipoEvento.FOTOGRAFIA);
        }
        cola.programar(minutoFin, TipoEvento.FIN_ESCENARIO);
    }

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

    private long vencimientoPendiente() {
        candado.lock();
        try {
            return estado.proximoVencimiento();
        } finally {
            candado.unlock();
        }
    }

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

                return true;
            }
        }
        return false;
    }

    private boolean drenarAveriasManuales() {
        boolean alguna = false;
        int[] manual;
        while ((manual = averiasManuales.poll()) != null) {

            cola.programar(minutoActual, TipoEvento.AVERIA, manual[0], -manual[1]);
            alguna = true;
        }
        return alguna;
    }

    private long milisegundosReales() {
        return nanoInicio == 0L ? 0L : (System.nanoTime() - nanoInicio) / 1_000_000L;
    }

    private String procesar(Evento evento) {
        switch (evento.tipo()) {
            case REPLANIFICACION -> {
                var motivos = motivosReplanificacion.remove(minutoActual);
                String detalle = motivos == null ? "SIN_MOTIVO_REGISTRADO" : String.join("|", motivos);
                long minuto = minutoActual;
                notificar(o -> o.alIniciarReplanificacion(minuto, detalle));
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
        for (int indice : vencidos) {
            Pedido pedido = estado.pedido(indice);
            int faltante = estado.noEntregadoDe(indice);
            notificar(o -> o.alIncumplirPedido(minutoActual, pedido.id(), faltante));
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

    private void recargarAlmacenes() {
        estado.recargarIntermedios();
        if (operacion) solicitarReplanificacion("REPOSICION");
        for (int i = 0; i < estado.almacenes().size(); i++) {
            revisarSemaforo(i);
        }
    }

    private void replanificarPorBloqueo() {
        solicitarReplanificacion("BLOQUEO");
    }

    private void solicitarReplanificacion(String motivo) {
        motivosReplanificacion.computeIfAbsent(minutoActual, k -> new LinkedHashSet<>()).add(motivo);
        cola.programar(minutoActual, TipoEvento.REPLANIFICACION);
    }

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
        unidad.minutoDisponibleDesde(minutoActual + CalendarioEscenario.MINUTOS_POR_DIA);
    }

    private void terminarMantenimiento(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        if (u.unidad().estado() != EstadoUnidad.EN_MANTENIMIENTO
                || operacion && u.unidad().minutoDisponibleDesde() > minutoActual) {
            return;
        }
        u.unidad().estado(EstadoUnidad.DISPONIBLE);
        u.unidad().minutoDisponibleDesde(minutoActual);
    }

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

        solicitarReplanificacion("AVERIA:" + codigo);
    }

    private boolean enOperacion(UnidadEnCurso u) {
        EstadoUnidad e = u.unidad().estado();
        return e == EstadoUnidad.EN_RUTA || e == EstadoUnidad.ENTREGANDO
                || e == EstadoUnidad.ABASTECIENDO || e == EstadoUnidad.EN_ALIMENTACION;
    }

    private void trasladarAlCentral(int indiceUnidad) {
        UnidadEnCurso u = estado.unidad(indiceUnidad);
        if (u.averia() == null) {
            return;
        }
        u.limpiarItinerario(estado.central().nodo());
        u.unidad().nodo(estado.central().nodo());
        u.unidad().cargaABordo(0);
    }

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
            long minuto = minutoActual;
            notificar(o -> o.alNoAsignarRescate(minuto, averiada.codigo(),
                    "Sin unidad disponible con capacidad libre y llegada/trasvase antes del traslado"));
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
                minutoActual, parametrosVigentes);
        elegida.marcarMisionTrasvase(averiada.indice());
        elegida.unidad().estado(EstadoUnidad.EN_RUTA);
        elegida.unidad().minutoDisponibleDesde(mejorLlegada + parametrosVigentes.minutosTrasvase());
        long secuencia = cola.programar(mejorLlegada, TipoEvento.LLEGADA_A_PARADA,
                elegida.indice(), PARADA_TRASVASE).secuencia();
        elegida.secuenciaMovimiento(secuencia);
        long minuto = minutoActual;
        long llegada = mejorLlegada;
        long fin = mejorLlegada + parametrosVigentes.minutosTrasvase();
        String codigoRescate = elegida.codigo();
        int capacidadLibre = elegida.unidad().capacidadLibre();
        notificar(o -> o.alDespacharRescate(minuto, averiada.codigo(), codigoRescate,
                llegada, fin, averiada.unidad().cargaABordo(), capacidadLibre));
    }

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
        long minuto = minutoActual;
        notificar(o -> o.alCompletarTrasvase(minuto, averiada.codigo(), rescate.codigo(),
                trasvasado, averiada.unidad().cargaABordo(), rescate.unidad().cargaABordo()));

        solicitarReplanificacion("FIN_TRASVASE:" + rescate.codigo());
    }

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
            long minuto = minutoActual;
            notificar(o -> o.alIniciarTrasvase(minuto, estado.unidad(u.unidadAsistida()).codigo(),
                    u.codigo(), u.minutoSalidaParada(k)));
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
            case ALIMENTACION -> {
                u.unidad().estado(EstadoUnidad.EN_ALIMENTACION);
                u.unidad().inicioTurnoAlimentacion(Turno.inicioDelTurno(minutoActual));
            }
            default -> throw new IllegalStateException("Tipo de parada no contemplado: " + parada.tipo());
        }
        programarMovimiento(u, u.minutoSalidaParada(k), TipoEvento.FIN_SERVICIO, k);
    }

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
        boolean completado = estado.entregar(indicePedido, cantidad, minutoActual);
        Pedido pedido = estado.pedido(indicePedido);
        long minuto = minutoActual;
        String codigo = u.unidad().codigo();
        int pendiente = estado.noEntregadoDe(indicePedido);
        notificar(o -> o.alDescargarPedido(minuto, pedido.id(), codigo, cantidad, pendiente));
        if (!completado) {
            return;
        }
        long desdeRegistro = minuto - pedido.minutoRegistro();
        notificar(o -> o.alEntregarPedido(minuto, pedido.id(), codigo, pedido.cantidad(),
                desdeRegistro, pedido.plazoHoras()));
    }

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

    private void programarMovimiento(UnidadEnCurso u, long minuto, TipoEvento tipo, int parada) {
        long secuencia = cola.programar(Math.max(minutoActual, minuto), tipo, u.indice(), parada).secuencia();
        u.secuenciaMovimiento(secuencia);
    }

    private void cobrarKilometros(UnidadEnCurso u, int indiceEnCamino) {
        estado.anotarRecorrido(u.unidad().tipo(), u.cobrarHasta(indiceEnCamino));
    }

    private void replanificar() {
        if (operacion && busqueda != null) {
            otraPlanificacion = true;
            return;
        }
        if (operacion && estado.pedidosPendientes() == 0) return;
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

        PresupuestoComputo presupuesto = (operacion
                ? PresupuestoComputo.deMilisegundos(presupuestoOperacionMs)
                : configuracion.presupuestoPlanificacion())
                .conReserva(PresupuestoComputo.RESERVA_CIERRE_MS).arrancar();
        presupuestoVigente = presupuesto;
        if (cancelado) {
            presupuesto.cancelar();
        }
        long semillaIteracion = Aleatorio.derivarSemilla(configuracion.semilla(), replanificacionesLanzadas++);
        if (operacion) {
            fotografiaEnCalculo = fotografia;
            revisionEnCalculo = revisionMundo;
            minutoEnCalculo = minutoActual;
            busqueda = buscador.submit(() -> fotografia.planVigente() == null
                    ? algoritmo.resolver(fotografia.instancia(), presupuesto, semillaIteracion)
                    : algoritmo.resolverDesde(fotografia.instancia(), presupuesto, semillaIteracion,
                            fotografia.planVigente()));
            return;
        }
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
        notificar(o -> o.alPublicarPlan(minuto, fotografia.instancia(), plan));
    }

    private record Fotografia(InstanciaPlanificacion instancia, List<UnidadEnCurso> unidades,
                              int[] nodoRedireccion, Solucion planVigente) {
    }

    private Fotografia construirInstancia(ParametrosOperacion.Instantanea foto, boolean conPlanVigente) {
        long minuto = minutoActual;
        int[] pendientes = estado.pendientesEn(minuto);
        List<Almacen> almacenes = estado.almacenes();

        List<UnidadEnCurso> planificables = new ArrayList<>(estado.unidades().length);
        for (UnidadEnCurso u : estado.unidades()) {

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

                nodoRedireccion[i] = u.nodoActual(minuto);
                minutoDisponible[i] = Math.max(minuto, u.minutoSalidaParadaEnCurso());
            } else if (u.indiceParada() >= 0) {

                int indice = u.indiceRedireccion(minuto);
                long llegadaNodo = minutoEnRedireccion(u, indice, foto);

                int fin = u.desplazamientoParada(u.indiceParada());
                while (llegadaNodo < minuto && indice < fin) {
                    llegadaNodo = minutoEnRedireccion(u, ++indice, foto);
                }
                nodoRedireccion[i] = u.nodoEn(indice);
                minutoDisponible[i] = Math.max(minuto, llegadaNodo);
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

            UnidadTransporte espejo = new UnidadTransporte(u.codigo(), u.unidad().tipo(), nodoRedireccion[i]);
            espejo.cargaABordo(u.unidad().cargaABordo());
            espejo.inicioTurnoAlimentacion(u.unidad().inicioTurnoAlimentacion());
            espejo.minutoDisponibleDesde(minutoDisponible[i]);
            constructor.unidad(espejo);
            for (Mantenimiento mantenimiento : mantenimientos) {
                long inicio = calendario.aMinutos(mantenimiento.fecha());
                if (mantenimiento.codigoUnidad().equals(u.codigo()) && inicio > minuto) {
                    constructor.mantenimiento(u.codigo(), inicio);
                }
            }
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

    private static long minutoEnRedireccion(UnidadEnCurso u, int indice, ParametrosOperacion.Instantanea foto) {
        return u.minutoLlegadaANodo(indice);
    }

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

        return new Solucion(rutas, banco, ValorObjetivo.PEOR);
    }

    private List<Parada> paradasPendientes(UnidadEnCurso u) {
        Ruta ruta = u.ruta();
        if (ruta == null || u.indiceParada() == UnidadEnCurso.SIN_PARADA) {
            return List.of();
        }
        int desde = u.indiceParada() < 0 ? 0 : (u.sirviendo() ? u.indiceParada() + 1 : u.indiceParada());
        List<Parada> paradas = ruta.paradas();
        return desde >= paradas.size() ? List.of() : paradas.subList(desde, paradas.size());
    }

    private void comprometer(Fotografia fotografia, Solucion solucion) {
        Map<String, Ruta> porUnidad = new HashMap<>(solucion.rutas().size() * 2);
        for (Ruta r : solucion.rutas()) {
            porUnidad.put(r.codigoUnidad(), r);
        }
        for (int i = 0; i < fotografia.unidades().size(); i++) {
            UnidadEnCurso u = fotografia.unidades().get(i);
            Ruta ruta = porUnidad.get(u.codigo());
            if ((ruta == null || ruta.paradas().isEmpty()) && fotografia.instancia().unidadRetornaAlCentral(i)) {
                ruta = new org.kindbox.core.evaluacion.ProgramadorRuta(fotografia.instancia())
                        .programar(i, new int[0], new int[0], 0, false).ruta();
            }
            comprometerUnidad(u, i, fotografia, ruta);
        }
    }

    private void comprometerUnidad(UnidadEnCurso u, int indiceEnInstancia, Fotografia fotografia, Ruta ruta) {
        long minuto = minutoActual;
        int indiceNodo = u.indiceNodoActual(minuto);
        int nodoFisico = u.nodoEn(indiceNodo);
        cobrarKilometros(u, indiceNodo);
        long finServicio = u.sirviendo() ? u.minutoSalidaParadaEnCurso() : Long.MIN_VALUE;
        u.invalidarMovimiento();

        if (ruta == null || ruta.paradas().isEmpty()) {

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
                desplazamiento, llegadas, salidas, salidaTramo, instancia.parametros());
        u.unidad().nodo(nodoFisico);
        if (finServicio > minuto) {
            u.marcarServicioHeredado(finServicio);
            programarMovimiento(u, finServicio, TipoEvento.FIN_SERVICIO, UnidadEnCurso.SERVICIO_HEREDADO);
        } else {
            u.unidad().estado(EstadoUnidad.EN_RUTA);
            programarMovimiento(u, llegadas[0], TipoEvento.LLEGADA_A_PARADA, 0);
        }
    }

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

            case ALIMENTACION -> -1;
        };
    }

    private static int indiceDeAlmacenEnInstancia(InstanciaPlanificacion instancia, int idAlmacen) {
        for (int i = 0; i < instancia.cantidadAlmacenes(); i++) {
            if (instancia.almacenId(i) == idAlmacen) {
                return i;
            }
        }
        return -1;
    }

    private void reiniciarTrabajo(int nodo) {
        longitudTrabajo = 0;
        agregarNodo(nodo);
    }

    private void agregarNodo(int nodo) {
        if (longitudTrabajo == caminoTrabajo.length) {
            caminoTrabajo = Arrays.copyOf(caminoTrabajo, longitudTrabajo * 2);
        }
        caminoTrabajo[longitudTrabajo++] = nodo;
    }

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

    private static int[] invertir(int[] camino) {
        int[] inverso = new int[camino.length];
        for (int i = 0; i < camino.length; i++) {
            inverso[i] = camino[camino.length - 1 - i];
        }
        return inverso;
    }

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

    private void notificar(Consumer<ObservadorSimulacion> aviso) {
        for (ObservadorSimulacion observador : observadores) {
            try {
                aviso.accept(observador);
            } catch (RuntimeException ignorado) {
                if (operacion) throw ignorado;
            }
        }
    }

    public List<TipoUnidad> tiposDeUnidad() {
        return List.of(TipoUnidad.values());
    }
}
