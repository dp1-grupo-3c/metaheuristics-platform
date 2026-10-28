package org.kindbox.service.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.service.operacion.CoordinadorEjecucion;
import org.kindbox.service.operacion.ServicioOperacion;
import org.kindbox.service.simulacion.RegistroAlgoritmos;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class ManejadorWebSocketOperacionTest {
    @TempDir Path datos;

    WebSocketSession sesion(String id, String contexto) {
        WebSocketSession sesion = mock(WebSocketSession.class);
        when(sesion.getId()).thenReturn(id);
        when(sesion.getUri()).thenReturn(URI.create("ws://localhost/ws/operaciones?contextoId=" + contexto));
        when(sesion.isOpen()).thenReturn(true);
        return sesion;
    }

    @Test void clienteLentoNoBloqueaAlRapidoYEsDesconectado() throws Exception {
        Files.writeString(datos.resolve("flota.txt"), "TA01\n");
        try (var servicio = new ServicioOperacion(new RepositorioDatos(datos), new RegistroAlgoritmos(),
                new CoordinadorEjecucion(), Clock.fixed(Instant.parse("2026-10-28T17:00:00Z"), ZoneOffset.UTC), false);
             var manejador = new ManejadorWebSocketOperacion(servicio, Jackson2ObjectMapperBuilder.json().build())) {
            String contexto = servicio.iniciar(null).contextoId();
            var lento = sesion("lento", contexto);
            var rapido = sesion("rapido", contexto);
            var envioLento = new CountDownLatch(1);
            var desbloquear = new CountDownLatch(1);
            var recibido = new CountDownLatch(1);
            var cerrado = new CountDownLatch(1);
            doAnswer(invocacion -> {
                envioLento.countDown();
                desbloquear.await(5, TimeUnit.SECONDS);
                return null;
            }).when(lento).sendMessage(any());
            doAnswer(invocacion -> {
                desbloquear.countDown();
                cerrado.countDown();
                return null;
            }).when(lento).close(any(CloseStatus.class));
            doAnswer(invocacion -> { recibido.countDown(); return null; }).when(rapido).sendMessage(any());
            manejador.afterConnectionEstablished(lento);
            assertTrue(envioLento.await(1, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> manejador.afterConnectionEstablished(rapido));
            assertTrue(recibido.await(1, TimeUnit.SECONDS));
            assertTrue(cerrado.await(4, TimeUnit.SECONDS));
            verify(lento, atLeastOnce()).close(any(CloseStatus.class));
        }
    }

    @Test void rechazaUnaSuscripcionAUnContextoInexistente() throws Exception {
        try (var servicio = new ServicioOperacion(new RepositorioDatos(datos), new RegistroAlgoritmos(),
                new CoordinadorEjecucion(), Clock.systemUTC(), false);
             var manejador = new ManejadorWebSocketOperacion(servicio, Jackson2ObjectMapperBuilder.json().build())) {
            var sesion = sesion("desconocida", "op-inexistente");
            manejador.afterConnectionEstablished(sesion);
            verify(sesion).close(CloseStatus.POLICY_VIOLATION);
            verify(sesion, never()).sendMessage(any());
        }
    }
}
