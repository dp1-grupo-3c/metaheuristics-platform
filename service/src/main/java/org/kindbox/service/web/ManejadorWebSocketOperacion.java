package org.kindbox.service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.kindbox.core.operacion.VistaOperacion;
import org.kindbox.service.operacion.ServicioOperacion;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class ManejadorWebSocketOperacion extends TextWebSocketHandler implements AutoCloseable {
    private static final int MAXIMAS_SESIONES = 32;
    private final ServicioOperacion servicio;
    private final ObjectMapper json;
    private final Map<String, Suscripcion> sesiones = new ConcurrentHashMap<>();
    private final ExecutorService envios = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService publicador = Executors.newSingleThreadScheduledExecutor(tarea -> {
        Thread hilo = new Thread(tarea, "publicador-operacion");
        hilo.setDaemon(true);
        return hilo;
    });

    public ManejadorWebSocketOperacion(ServicioOperacion servicio, ObjectMapper json) {
        this.servicio = servicio;
        this.json = json;
        publicador.scheduleWithFixedDelay(this::difundir, 1, 1, TimeUnit.SECONDS);
    }

    private static final class Suscripcion {
        final WebSocketSession sesion;
        final String contexto;
        final AtomicBoolean enviando = new AtomicBoolean();
        volatile long inicioEnvio;
        volatile long revision = -1;

        Suscripcion(WebSocketSession sesion, String contexto) {
            this.sesion = new ConcurrentWebSocketSessionDecorator(sesion, 1000, 512 * 1024);
            this.contexto = contexto;
        }
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession sesion) throws IOException {
        if (sesiones.size() >= MAXIMAS_SESIONES || sesion.getUri() == null) {
            sesion.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String contexto = UriComponentsBuilder.fromUri(sesion.getUri()).build().getQueryParams().getFirst("contextoId");
        VistaOperacion vista;
        try { vista = servicio.detalle(contexto); }
        catch (RuntimeException e) {
            sesion.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Suscripcion suscripcion = new Suscripcion(sesion, contexto);
        sesiones.put(sesion.getId(), suscripcion);
        enviar(suscripcion, vista);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        sesiones.remove(sesion.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        Suscripcion suscripcion = sesiones.remove(sesion.getId());
        if (suscripcion != null) cerrar(suscripcion);
    }

    public void difundir() {
        for (Suscripcion suscripcion : sesiones.values()) {
            if (suscripcion.enviando.get()) {
                if (System.nanoTime() - suscripcion.inicioEnvio > TimeUnit.SECONDS.toNanos(2)) {
                    sesiones.remove(suscripcion.sesion.getId());
                    envios.submit(() -> cerrar(suscripcion));
                }
                continue;
            }
            try { enviar(suscripcion, servicio.detalle(suscripcion.contexto)); }
            catch (RuntimeException e) {
                sesiones.remove(suscripcion.sesion.getId());
                envios.submit(() -> cerrar(suscripcion));
            }
        }
    }

    private void enviar(Suscripcion suscripcion, VistaOperacion vista) {
        if (suscripcion.revision >= vista.revision() || !suscripcion.enviando.compareAndSet(false, true)) return;
        suscripcion.inicioEnvio = System.nanoTime();
        envios.submit(() -> {
            try {
                if (!suscripcion.sesion.isOpen()) {
                    sesiones.remove(suscripcion.sesion.getId());
                    return;
                }
                suscripcion.sesion.sendMessage(new TextMessage(json.writeValueAsString(vista)));
                suscripcion.revision = vista.revision();
            } catch (IOException | RuntimeException e) {
                sesiones.remove(suscripcion.sesion.getId());
                cerrar(suscripcion);
            } finally {
                suscripcion.enviando.set(false);
            }
        });
    }

    private void cerrar(Suscripcion suscripcion) {
        try { suscripcion.sesion.close(CloseStatus.SESSION_NOT_RELIABLE); }
        catch (IOException | RuntimeException ignorado) { }
    }

    @Override
    @PreDestroy
    public void close() {
        publicador.shutdownNow();
        sesiones.values().forEach(this::cerrar);
        sesiones.clear();
        envios.shutdownNow();
    }
}
