package org.kindbox.service.operacion;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.operacion.ComandoOperacion;
import org.kindbox.core.operacion.ConfiguracionOperacion;
import org.kindbox.core.operacion.MotorOperacion;
import org.kindbox.core.operacion.SolicitudAveriaOperacion;
import org.kindbox.core.operacion.SolicitudParametrosOperacion;
import org.kindbox.core.operacion.SolicitudPedidoOperacion;
import org.kindbox.core.operacion.VistaOperacion;
import org.kindbox.core.operacion.VistaPedidoOperacion;
import org.kindbox.service.dto.PaginaPedidosOperacion;
import org.kindbox.service.dto.SolicitudOperacion;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.error.SolicitudInvalida;
import org.kindbox.service.simulacion.RegistroAlgoritmos;
import org.springframework.stereotype.Service;

@Service
public class ServicioOperacion implements AutoCloseable {
    private final RepositorioDatos repositorio;
    private final RegistroAlgoritmos algoritmos;
    private final CoordinadorEjecucion coordinador;
    private final Clock reloj;
    private final Map<String, MotorOperacion> operaciones = new LinkedHashMap<>();
    private final ScheduledExecutorService ejecutor;
    private MotorOperacion actual;
    private CoordinadorEjecucion.Reserva reserva;

    @org.springframework.beans.factory.annotation.Autowired
    public ServicioOperacion(RepositorioDatos repositorio, RegistroAlgoritmos algoritmos,
                              CoordinadorEjecucion coordinador) {
        this(repositorio, algoritmos, coordinador, Clock.systemUTC(), true);
    }

    public ServicioOperacion(RepositorioDatos repositorio, RegistroAlgoritmos algoritmos,
                              CoordinadorEjecucion coordinador, Clock reloj, boolean automatico) {
        this.repositorio = repositorio;
        this.algoritmos = algoritmos;
        this.coordinador = coordinador;
        this.reloj = reloj;
        ejecutor = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "operacion-diaria");
            hilo.setDaemon(true);
            return hilo;
        });
        if (automatico) ejecutor.scheduleWithFixedDelay(this::actualizar, 100, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized VistaOperacion iniciar(SolicitudOperacion solicitud) {
        if (actual != null && actual.vista().estado() == VistaOperacion.Estado.EN_CURSO) {
            throw new ConflictoDeEstado("Ya existe una operacion en curso");
        }
        SolicitudOperacion entrada = solicitud == null ? new SolicitudOperacion(null, null, null, null, null) : solicitud;
        ConfiguracionOperacion defecto = ConfiguracionOperacion.porDefecto();
        ConfiguracionOperacion configuracion;
        try {
            configuracion = new ConfiguracionOperacion(
                    entrada.zonaHoraria() == null ? defecto.zonaHoraria() : ZoneId.of(entrada.zonaHoraria()),
                    entrada.saltoMinutos() == null ? defecto.saltoMinutos() : entrada.saltoMinutos(),
                    algoritmos.canonico(entrada.algoritmo() == null ? defecto.algoritmo() : entrada.algoritmo()),
                    entrada.semilla() == null ? defecto.semilla() : entrada.semilla(),
                    entrada.presupuestoPlanificacionMs() == null ? defecto.presupuestoPlanificacionMs()
                            : entrada.presupuestoPlanificacionMs());
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            throw new SolicitudInvalida(e.getMessage(), e);
        }
        CoordinadorEjecucion.Reserva nuevaReserva = coordinador.reservar("operaciones diarias");
        try {
            String id = "op-" + UUID.randomUUID();
            MotorOperacion motor = new MotorOperacion(id, configuracion, reloj, repositorio::cargarEntorno,
                    algoritmos.crear(configuracion.algoritmo(), configuracion.semilla()));
            actual = motor;
            operaciones.put(id, motor);
            reserva = nuevaReserva;
            while (operaciones.size() > 20) {
                String primero = operaciones.keySet().iterator().next();
                operaciones.remove(primero).close();
            }
            liberarSiTermino();
            return motor.vista();
        } catch (IOException e) {
            nuevaReserva.close();
            throw new ErrorDeDatos("No se pudo cargar el entorno operativo", e);
        } catch (RuntimeException e) {
            nuevaReserva.close();
            throw e;
        }
    }

    public synchronized void actualizar() {
        if (actual == null) return;
        actual.actualizar();
        liberarSiTermino();
    }

    public synchronized VistaOperacion actual() {
        if (actual == null) throw new RecursoNoEncontrado("No hay ninguna operacion registrada");
        return actual.vista();
    }

    public synchronized VistaOperacion detalle(String id) { return requerir(id).vista(); }

    public synchronized VistaOperacion detener(String id) {
        MotorOperacion motor = requerir(id);
        if (motor.vista().estado() != VistaOperacion.Estado.EN_CURSO) {
            throw new ConflictoDeEstado("La operacion ya termino");
        }
        motor.close();
        liberarSiTermino();
        return motor.vista();
    }

    public synchronized ComandoOperacion.Resultado aceptar(String id, SolicitudPedidoOperacion solicitud) {
        try { return requerir(id).aceptar(solicitud); }
        catch (IllegalStateException e) { throw new ConflictoDeEstado(e.getMessage()); }
    }

    public synchronized ComandoOperacion.Resultado aceptar(String id, SolicitudAveriaOperacion solicitud) {
        try { return requerir(id).aceptar(solicitud); }
        catch (IllegalStateException e) { throw new ConflictoDeEstado(e.getMessage()); }
    }

    public synchronized ComandoOperacion.Resultado aceptar(String id, SolicitudParametrosOperacion solicitud) {
        try { return requerir(id).aceptar(solicitud); }
        catch (IllegalStateException e) { throw new ConflictoDeEstado(e.getMessage()); }
    }

    public synchronized ComandoOperacion.Resultado comando(String id, String comandoId) {
        var resultado = requerir(id).comando(comandoId);
        if (resultado == null) throw new RecursoNoEncontrado("El comando no existe en esta operacion");
        return resultado;
    }

    public synchronized PaginaPedidosOperacion pedidos(String id, int pagina, int tamano, String busqueda,
                                                        boolean soloActivos) {
        if (pagina < 0 || tamano < 1 || tamano > 500) {
            throw new SolicitudInvalida("La pagina debe ser positiva o cero y el tamano estar entre 1 y 500");
        }
        VistaOperacion vista = requerir(id).vista();
        String filtro = busqueda == null ? "" : busqueda.trim().toLowerCase(Locale.ROOT);
        var filas = vista.pedidos().stream().filter(p -> !soloActivos || p.pendiente() > 0 && !p.incumplido())
                .filter(p -> coincide(p, filtro)).toList();
        int desde = (int) Math.min((long) pagina * tamano, filas.size());
        int hasta = Math.min(desde + tamano, filas.size());
        return new PaginaPedidosOperacion(id, vista.revision(), filas.subList(desde, hasta), pagina, tamano,
                filas.size(), (filas.size() + tamano - 1) / tamano);
    }

    private boolean coincide(VistaPedidoOperacion pedido, String filtro) {
        return filtro.isEmpty() || (pedido.id() + " " + pedido.codigoCliente() + " " + pedido.nombreCliente()
                + " " + pedido.direccion() + " " + pedido.estado()).toLowerCase(Locale.ROOT).contains(filtro);
    }

    private MotorOperacion requerir(String id) {
        MotorOperacion motor = operaciones.get(id);
        if (motor == null) throw new RecursoNoEncontrado("La operacion no existe");
        return motor;
    }

    private void liberarSiTermino() {
        if (reserva != null && actual != null && actual.vista().estado() != VistaOperacion.Estado.EN_CURSO) {
            reserva.close();
            reserva = null;
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        ejecutor.shutdownNow();
        operaciones.values().forEach(MotorOperacion::close);
        if (reserva != null) reserva.close();
    }
}
