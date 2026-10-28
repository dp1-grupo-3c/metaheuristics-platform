package org.kindbox.core.operacion;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.metaheuristica.Algoritmo;
import org.kindbox.core.metaheuristica.ResultadoPlanificacion;
import org.kindbox.core.modelo.EstadoUnidad;
import org.kindbox.core.modelo.TipoAveria;
import org.kindbox.core.problema.InstanciaPlanificacion;
import org.kindbox.core.simulacion.ConfiguracionEscenario;
import org.kindbox.core.simulacion.ModoReloj;
import org.kindbox.core.simulacion.MotorEjecucion;
import org.kindbox.core.simulacion.ObservadorSimulacion;
import org.kindbox.core.simulacion.TipoEscenario;

public final class MotorOperacion implements AutoCloseable, ObservadorSimulacion {
    private static final int MAXIMOS_COMANDOS_PENDIENTES = 4096;
    private final ConfiguracionOperacion configuracion;
    private final ContextoOperacion contexto;
    private final RelojOperacion reloj;
    private final FuenteEntornoOperacion fuente;
    private final MotorEjecucion ejecucion;
    private final ExecutorService buscador;
    private final Map<String, ComandoOperacion> comandos = new LinkedHashMap<>();
    private final Deque<ComandoOperacion> pendientes = new ArrayDeque<>();
    private final Map<Integer, Set<String>> asignaciones = new LinkedHashMap<>();
    private final List<VistaOperacion.Incidente> incidentes = new ArrayList<>();
    private final Set<String> avisos = new LinkedHashSet<>();
    private final Set<org.kindbox.core.modelo.Bloqueo> bloqueosConocidos = new LinkedHashSet<>();
    private LocalDate fechaEntorno;
    private LocalDate agendadoHasta;
    private volatile VistaOperacion vista;
    private String error;

    public MotorOperacion(String id, ConfiguracionOperacion configuracion, Clock reloj,
                          FuenteEntornoOperacion fuente, Algoritmo algoritmo) throws IOException {
        this.configuracion = java.util.Objects.requireNonNull(configuracion);
        contexto = new ContextoOperacion(id);
        this.reloj = new RelojOperacion(reloj, configuracion.zonaHoraria());
        this.fuente = java.util.Objects.requireNonNull(fuente);
        LocalDate hoy = this.reloj.fechaHora().toLocalDate();
        RepositorioDatos.DatosEscenario datos = fuente.cargar(hoy.minusDays(1), hoy.plusDays(3), this.reloj.calendario());
        var escenario = new ConfiguracionEscenario(TipoEscenario.DIA_A_DIA, hoy, hoy, configuracion.saltoMinutos(),
                1, ModoReloj.LIBRE, configuracion.algoritmo(), configuracion.semilla(), 1, false, 0, true);
        ejecucion = new MotorEjecucion(datos, escenario, contexto.parametros(), algoritmo, List.of(this), true);
        buscador = Executors.newSingleThreadExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "planificador-operacion-" + id);
            hilo.setDaemon(true);
            return hilo;
        });
        try {
            ejecucion.iniciarOperacion(this.reloj.minuto(), buscador, configuracion.presupuestoPlanificacionMs());
            cargarAgenda(hoy, datos);
            actualizar();
        } catch (RuntimeException e) {
            buscador.shutdownNow();
            throw e;
        }
    }

    public synchronized ComandoOperacion.Resultado aceptar(SolicitudPedidoOperacion solicitud) {
        return aceptar(solicitud.solicitudId(), solicitud);
    }

    public synchronized ComandoOperacion.Resultado aceptar(SolicitudAveriaOperacion solicitud) {
        if (ejecucion.estado().indiceDeUnidad(solicitud.placa()) < 0) {
            throw new IllegalArgumentException("La placa no pertenece a la flota operativa");
        }
        return aceptar(solicitud.solicitudId(), solicitud);
    }

    public synchronized ComandoOperacion.Resultado aceptar(SolicitudParametrosOperacion solicitud) {
        return aceptar(solicitud.solicitudId(), solicitud);
    }

    private ComandoOperacion.Resultado aceptar(String id, Object contenido) {
        ComandoOperacion existente = comandos.get(id);
        if (existente != null) {
            if (!existente.contenido().equals(contenido)) {
                throw new IllegalStateException("El identificador de solicitud ya fue usado con otro contenido");
            }
            return existente.resultado();
        }
        exigirEnCurso();
        if (pendientes.size() >= MAXIMOS_COMANDOS_PENDIENTES) {
            throw new IllegalStateException("La cola operativa esta llena");
        }
        ComandoOperacion comando = new ComandoOperacion(id, contenido, reloj.instante());
        comandos.put(id, comando);
        pendientes.addLast(comando);
        return comando.resultado();
    }

    public synchronized ComandoOperacion.Resultado comando(String id) {
        ComandoOperacion comando = comandos.get(id);
        return comando == null ? null : comando.resultado();
    }

    public VistaOperacion vista() { return vista; }

    public synchronized void actualizar() {
        if (contexto.estado() != VistaOperacion.Estado.EN_CURSO) return;
        try {
            LocalDate fecha = reloj.fechaHora().toLocalDate();
            if (!fecha.equals(fechaEntorno)) {
                LocalDate desde = fechaEntorno.minusDays(1);
                var datos = fuente.cargar(desde, fecha.plusDays(3), reloj.calendario());
                cargarAgenda(fecha, datos);
            }
            long minuto = Math.max(ejecucion.minutoActual(), reloj.minuto());
            ejecucion.avanzarOperacion(minuto);
            ComandoOperacion comando;
            while ((comando = pendientes.pollFirst()) != null) aplicar(comando);
            ejecucion.avanzarOperacion(minuto);
            actualizarEstadosEnTransito();
            publicar();
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            contexto.estado(VistaOperacion.Estado.FALLIDA);
            rechazarPendientes("La operacion fallo antes de aplicar el comando");
            ejecucion.fallarOperacion();
            buscador.shutdownNow();
            publicar();
        }
    }

    private void cargarAgenda(LocalDate fecha, RepositorioDatos.DatosEscenario datos) {
        bloqueosConocidos.removeIf(bloqueo -> bloqueo.minutoFin() <= ejecucion.minutoActual());
        bloqueosConocidos.addAll(datos.bloqueos());
        List<org.kindbox.core.modelo.Bloqueo> bloqueos = List.copyOf(bloqueosConocidos);
        ejecucion.actualizarEntorno(bloqueos, datos.mantenimientos());
        avisos.addAll(datos.avisos());
        LocalDate desde = agendadoHasta == null ? fecha : agendadoHasta.plusDays(1);
        LocalDate hasta = fecha.plusDays(3);
        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            ejecucion.agendarDia(reloj.calendario().aMinutos(dia), bloqueos);
        }
        agendadoHasta = hasta;
        fechaEntorno = fecha;
    }

    private void aplicar(ComandoOperacion comando) {
        try {
            Integer pedidoId = null;
            Object contenido = comando.contenido();
            if (contenido instanceof SolicitudPedidoOperacion solicitud) {
                PedidoOperativo pedido = contexto.pedidos().crear(solicitud, ejecucion.minutoActual(), reloj.calendario());
                ejecucion.incorporarPedido(pedido.pedido());
                pedidoId = pedido.pedido().id();
            } else if (contenido instanceof SolicitudAveriaOperacion solicitud) {
                ejecucion.aplicarAveria(solicitud.placa(), TipoAveria.porCodigo(solicitud.tipo()));
                long inicio = ejecucion.minutoActual();
                incidentes.add(new VistaOperacion.Incidente(comando.id(), solicitud.placa(), solicitud.tipo(),
                        reloj.calendario().aFecha(inicio),
                        reloj.calendario().aFecha(TipoAveria.porCodigo(solicitud.tipo()).minutoReincorporacion(inicio)), false));
            } else if (contenido instanceof SolicitudParametrosOperacion solicitud) {
                for (var entrada : solicitud.velocidades().entrySet()) {
                    contexto.parametros().velocidad(entrada.getKey(), entrada.getValue());
                }
                if (solicitud.ambar() != null) contexto.parametros().umbralesSemaforo(solicitud.ambar(), solicitud.verde());
                ejecucion.solicitarPlanOperacion();
            } else {
                throw new IllegalArgumentException("El comando operativo no esta reconocido");
            }
            comando.aplicar(reloj.instante(), pedidoId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            comando.rechazar(reloj.instante(), e.getMessage());
        }
    }

    private void actualizarEstadosEnTransito() {
        for (var unidad : ejecucion.estado().unidades()) {
            if (unidad.unidad().estado() != EstadoUnidad.EN_RUTA || unidad.unidad().cargaABordo() <= 0) continue;
            for (var entrada : asignaciones.entrySet()) {
                if (!entrada.getValue().contains(unidad.codigo())) continue;
                PedidoOperativo pedido = contexto.pedidos().buscar(entrada.getKey());
                if (!pedido.activo()) continue;
                EstadoPedido estado = pedido.vista().estado();
                if (estado == EstadoPedido.PLANIFICADO || estado == EstadoPedido.EN_REPLANIFICACION) {
                    pedido.cambiar(EstadoPedido.EN_TRANSITO, ejecucion.minutoActual());
                }
            }
        }
    }

    private void publicar() {
        var instantanea = ejecucion.instantanea();
        List<VistaOperacion.Incidente> historial = incidentes.stream().map(incidente ->
                new VistaOperacion.Incidente(incidente.comandoId(), incidente.placa(), incidente.tipo(),
                        incidente.inicio(), incidente.reincorporacion(),
                        !instantanea.fechaHoraSimulada().isBefore(incidente.reincorporacion()))).toList();
        vista = new VistaOperacion(contexto.id(), contexto.siguienteRevision(), contexto.estado(),
                configuracion.zonaHoraria().getId(), reloj.instante(), instantanea.fechaHoraSimulada(),
                configuracion, instantanea, contexto.parametros().instantanea(), contexto.pedidos().vistas(),
                historial, List.copyOf(avisos), ejecucion.calculando(), ejecucion.planesDescartados(), error);
    }

    private void exigirEnCurso() {
        if (contexto.estado() != VistaOperacion.Estado.EN_CURSO) {
            throw new IllegalStateException("La operacion ya termino");
        }
    }

    private void rechazarPendientes(String motivo) {
        ComandoOperacion comando;
        while ((comando = pendientes.pollFirst()) != null) comando.rechazar(reloj.instante(), motivo);
    }

    @Override
    public synchronized void close() {
        if (contexto.estado() != VistaOperacion.Estado.EN_CURSO) return;
        ejecucion.detenerOperacion();
        contexto.estado(VistaOperacion.Estado.DETENIDA);
        rechazarPendientes("La operacion fue detenida antes de aplicar el comando");
        buscador.shutdownNow();
        publicar();
    }

    @Override
    public void alPublicarPlan(long minuto, InstanciaPlanificacion instancia, ResultadoPlanificacion plan) {
        Map<Integer, Set<String>> nuevas = new LinkedHashMap<>();
        for (var ruta : plan.solucion().rutas()) {
            for (var parada : ruta.paradas()) {
                if (parada.tipo() == org.kindbox.core.problema.TipoParada.ENTREGA) {
                    nuevas.computeIfAbsent(parada.idPedido(), id -> new LinkedHashSet<>()).add(ruta.codigoUnidad());
                }
            }
        }
        for (var vista : contexto.pedidos().vistas()) {
            PedidoOperativo pedido = contexto.pedidos().buscar(vista.id());
            if (!pedido.activo()) continue;
            if (nuevas.containsKey(vista.id()) && (vista.estado() == EstadoPedido.REGISTRADO
                    || vista.estado() == EstadoPedido.EN_REPLANIFICACION)) {
                pedido.cambiar(EstadoPedido.PLANIFICADO, minuto);
            } else if (!nuevas.containsKey(vista.id()) && (vista.estado() == EstadoPedido.PLANIFICADO
                    || vista.estado() == EstadoPedido.EN_TRANSITO)) {
                pedido.cambiar(EstadoPedido.EN_REPLANIFICACION, minuto);
            }
        }
        asignaciones.clear();
        asignaciones.putAll(nuevas);
    }

    @Override
    public void alDescargarPedido(long minuto, int idPedido, String unidad, int cantidad, int pendiente) {
        PedidoOperativo pedido = contexto.pedidos().buscar(idPedido);
        if (pedido != null) pedido.descargar(pendiente, minuto);
    }

    @Override
    public void alIncumplirPedido(long minuto, int idPedido, int cantidad) {
        PedidoOperativo pedido = contexto.pedidos().buscar(idPedido);
        if (pedido != null) pedido.incumplir();
    }

    @Override
    public void alAveriarse(long minuto, String codigo, int tipo, int x, int y, int carga) {
        for (var entrada : asignaciones.entrySet()) {
            if (entrada.getValue().contains(codigo)) {
                PedidoOperativo pedido = contexto.pedidos().buscar(entrada.getKey());
                if (pedido.activo()) pedido.cambiar(EstadoPedido.EN_REPLANIFICACION, minuto);
            }
        }
    }
}
